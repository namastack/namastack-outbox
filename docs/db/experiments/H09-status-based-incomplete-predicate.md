# H09: Status-based "incomplete" predicate (`status IN ('NEW','FAILED')` / `status <> 'COMPLETED'`) instead of `completed_at IS NULL`
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on and off), `findOldestDueNextRetryAtByPartitions` (filter on).
Builds on: H04 / H05 (query shapes and the two partial indexes), compared with H06 / H08 (probe on the existing key index).
Origin: user idea, G1 iteration 2. Files: `experiments/H09/` (`indexes-a.sql`, `indexes-b.sql`, `queries-a/`, `queries-b/`, `implication.sql`, `run-implication.sh`).

## Change

Define "incomplete" by status instead of `completed_at IS NULL` in the predecessor probe (and, where a partial index is used, in its predicate):

- **variant a**: `status IN ('NEW', 'FAILED')`
- **variant b**: `status <> 'COMPLETED'`

**Primary scope (non-partial indexes only; user clarification):** no partial index; schema = baseline, plus optionally the H10 per-key index and one status-leading outer index.
Index states (`experiments/H09/`): `base` = baseline schema (`record_key_completed_created` kept, `record_key_created` kept); `h10` = + `idx_outbox_record_record_key_status_created
(record_key, status, created_at, id)` replacing `record_key_created` (`indexes-n-h10.sql`); `outer` = h10 + `idx_outbox_record_status_created (status, created_at, id) INCLUDE (partition_no,
next_retry_at, record_key)` (`indexes-n-outer.sql`, my addition: with `status = :status` as equality prefix it gives the ordered early-stop scan the partial index gave, on every dialect).

Queries (`queries-na/` = a, `queries-nb/` = b; no redundant predicate on the outer row, so the estimate comes from `status = :status` alone):

```sql
-- keys on (H04 head-of-key form, real LIMIT)
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND NOT EXISTS (SELECT 1 FROM outbox_record older
                  WHERE older.record_key = o.record_key
                    AND older.status IN ('NEW','FAILED')            -- b: older.status <> 'COMPLETED'
                    AND older.created_at <= o.created_at
                    AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))
ORDER BY o.created_at, o.id LIMIT :batchSize
-- keys off: same, probe = "earlier.status = :status AND earlier.next_retry_at <= :now ..." (no completed_at / incomplete predicate at all)
-- *_filter_orig: the unrewritten original (GROUP BY, ORDER BY MIN, no LIMIT) with only the predicate swapped
-- MIN on: H04 text with the swapped probe predicate
```

With `older.status IN ('NEW','FAILED') AND older.created_at <= o.created_at` the H10 index `(record_key, status, created_at, id)` serves the probe as two range scans (one per status);
variant b (`<>`) is not an index condition and is evaluated as a filter over the key's rows.

**Side note (partial indexes with a status predicate; `indexes-a.sql`, `indexes-b.sql`, `queries-a/`, `queries-b/`):** H04's two partial indexes with `WHERE status IN ('NEW','FAILED')` (H09a) /
`WHERE status <> 'COMPLETED'` (H09b), explicit predicate kept in the query text for generic plans; `_nofilter_implied` has no incomplete predicate at all and relies on predicate implication.

## Rationale / expected plan

1. Misestimate: for H04's filter-off D case the planner multiplies `P(status='NEW') x P(completed_at IS NULL)` (343 rows
   estimated, 19,000 actual). With the status-based predicate the clauses are on **one column**. For a
   `status = 'NEW' AND status IN ('NEW','FAILED')` pair Postgres still multiplies the two selectivities (only range
   clauses on one column are combined), so keeping both is expected NOT to fix the estimate, while `_nofilter_implied`
   (only `status = 'NEW'`, MCV-accurate 2%) should. The partial index then has to be recognised through implication.
2. Implication: `status = 'NEW'` => `status IN ('NEW','FAILED')` is provable by `predicate_implied_by` (ScalarArrayOp
   OR-expansion); `status = 'NEW'` => `status <> 'COMPLETED'` is expected to be provable through the btree-operator
   table (`=` implies `<>` for a different constant) - to be verified. With a bound parameter in a generic plan nothing
   is provable (`status = $1`), hence the explicit predicate in the query text.
3. Index size / write cost: same row set as H04 (NEW + FAILED), so size and WAL should equal H04 (+13% insert WAL);
   updates `NEW -> COMPLETED` leave the partial index in the same way (HOT is impossible either way because `status` is
   in an indexed column; H04 had the same).

## Semantic equivalence argument

Equivalent to H04/H05 under the invariant **status in {NEW, FAILED} <=> completed_at IS NULL** (and status COMPLETED
<=> completed_at set), which the original SQL already relies on for blocking (`older.completed_at IS NULL`). Verified in
the core code (namastack-outbox-core):
- creation: `OutboxRecord.Builder.build` sets `status = NEW`, `completedAt = null`.
- `markCompleted`: sets `completedAt = now` **and** `status = COMPLETED` together (only if not already COMPLETED).
- `markFailed`: sets `status = FAILED`, never touches `completedAt` (stays null). A FAILED record is never re-processed
  (`canBeRetried` requires NEW), so nothing sets `completedAt` on a FAILED record.
- retries (`scheduleNextRetry`) only change `nextRetryAt`/`failureCount`; status stays NEW.
- `OutboxRecordProcessor.completeRecord` restores both fields in memory when the DB update fails (the DB write is a single
  `UPDATE ... SET status, completed_at, ...` in `updateProcessingState`/`update`, so the two columns never diverge in the DB).
- the JDBC/JPA/Mongo mappings persist `status` and `completedAt` together from the record; there is no other writer
  (cleanup/`deleteByStatus` only delete).
Not covered by the invariant: rows written by hand or by older/other writers with an inconsistent pair; `IN
('NEW','FAILED')` (a) would treat a future fourth status as complete, (b) `<> 'COMPLETED'` as incomplete (b is the safer
default for blocking: unknown states block rather than being skipped, but would bloat the partial index if such a
status were terminal).

## Portability

- postgres: both forms. Predicate implication for `<>` to be confirmed (`implication.sql`).
- sqlserver: filtered-index predicates allow comparison operators and `IN` (limited: simple comparisons joined by `AND`,
  `IN`; `OR` is not allowed, `<>` is allowed) - verify in Phase 3; the optimizer matches a filtered index only when the
  query predicate is a subset and with parameters it usually needs `OPTION (RECOMPILE)`, i.e. the explicit literal
  predicate in the query text (as in H09) is required, which H09 already has.
- mysql, mariadb, oracle: no partial indexes. Use a non-partial status-leading composite, e.g. `(status, created_at, id)`
  with the query `status IN ('NEW','FAILED')` (range/ref per status, ordered per status, so the outer ORDER BY across
  two statuses needs a merge or `UNION ALL`); compare with H07's `(completed_at, created_at, id)` which has one ordered
  section for all incomplete rows. For the key query with the bound status = NEW, `(status, created_at, id)` is already ordered.
- jpa: `@Table(indexes)` cannot express the partial predicate; HQL `status in (:incomplete)` or `status <> 'COMPLETED'`.
- mongo: partial index filter supports `$in` but not `$ne`/`$nin` (only equality, `$exists:true`, `$gt/$gte/$lt/$lte`, `$type`, `$and`, `$in` on newer
  versions); H09a only, otherwise stay on `completedAt: null`/`$exists`.

## Experiment plan

States per scenario (in place, `G1-iter2/run-scenario-n.sh`, `run-scenario-o.sh`): `base -> h10` (queries a and b), then `outer` (a and b); scenarios A, B, D, E (C only for the
partial side note) x parts 64/256: serial custom, serial generic, `--costs` (estimate vs actual), worst case `partlist_override=999`, batch 100/1000 on B, D. Partial side note: `run-scenario.sh`.
Implication matrix (`implication.sql`, B). Equivalence as H05 (all seeds + edge, key_ties 0/1, custom + generic), Write cost on A and B, fresh containers.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method: as H05-H08 (`G1-iter2/`, one container per scenario, in-place index states, 1 warm-up + 3 runs median, serial = no workers; tables `results/G1i2-tables.md`, labels
`results/H09n{a,b}-{base,h10,outer}-<S>/`, partial side note `results/H09{a,b}-<S>/`). C was not run for the non-partial states.

**(1) Misestimate (D, keys off, 64p; estimate vs actual rows of the `earlier` side; `--costs`, `results/*-D/findRecordKeysInPartitions_nofilter*-costs-serial.txt`):**

| form | `earlier` est. -> actual | plan | ms 64p / 256p |
|---|---|---|---|
| H04 `completed_at IS NULL AND status='NEW'` (ref) | 432 -> 19,000 | Materialize + join filter | 14.0 / 14.3 |
| a, partial, explicit `status IN (NEW,FAILED)` + `status='NEW'` | 439 -> 19,000 | Hash Anti Join on `idx_status` (partial index not used) | 12.2 / 15.2 |
| b, partial, explicit `status <> COMPLETED` + `status='NEW'` | 392 -> 19,000 | Materialize (as H04) | 14.3 / 13.9 |
| a or b, partial, **`_implied`** (only `status='NEW'`) | ~4,400-5,000 (outer) / ok | NL Anti Join + `incomplete_key` probe | **0.085 / 0.098** (a), **0.087 / 0.099** (b) |
| non-partial `outer` index, no incomplete predicate on `earlier` | ok (4,614 outer) | NL Anti Join, `status_created` + `record_key_status_created` probe | **0.091 / 0.098** (a), 0.099 / 0.097 (b) |
| non-partial `base` (no ordered outer index) | 20,011 -> 19,000 (correct) | Hash Anti Join, Sort top-N | 33 / 125 (a), 33 / 127 (b) |

The status x completed_at independence misestimate disappears **only if `completed_at` (or a second status condition on the same column) is not part of the filter**: stacking
`status = 'NEW' AND status IN ('NEW','FAILED')` still multiplies both selectivities (439 vs 19,000), no better than H04. A single `status = :status` is estimated from the status MCV (correct).

**(2)/(3) Latency, non-partial states** (custom-serial, ms, 64p / 256p; baseline = `G1ctl`: keys on A 5.3/20.3, B 266/428, D 38/139, E 5.7/19.9; keys off A 1.4/2.8, B 56/74, D 4.7/5.9, E 1.5/2.4; H04 partial: keys on 0.17/0.26, 0.20/0.36, 0.09/0.09, 0.27/0.61; keys off 0.12, 0.11-0.16, 14, 0.12):

| state | keys on A | B | D | E | keys off A | B | D | E | MIN on B | D |
|---|---|---|---|---|---|---|---|---|---|---|
| a base (baseline schema) | 3.4 / 4.8 | 59 / 87 | 33 / 127 | 3.6 / 5.0 | 3.4 / 4.9 | 59 / 86 | 33 / 125 | 3.4 / 4.8 | 0.26 / 0.65 | 0.86 / 1.7 |
| a + H10 key index | 3.4 / 4.9 | 59 / 86 | 33 / 127 | 3.5 / 4.8 | 3.4 / 4.6 | 59 / 91 | 32 / 125 | 3.5 / 4.7 | 0.17 / 0.40 | 0.07 / 0.10 |
| **a + H10 + `(status,created_at,id)` outer** | **0.21 / 0.40** | **0.21 / 0.41** | **0.10 / 0.13** | **0.32 / 0.94** | **0.10 / 0.11** | **0.10 / 0.10** | **0.09 / 0.10** | **0.10 / 0.12** | 0.17 / 0.35 | 0.07 / 0.11 |
| b base | 1.7 / 3.6 | 60 / 88 | 7.0 / 12.7 | 2.0 / 5.8 | 3.4 / 5.1 | 62 / 85 | 33 / 127 | 3.5 / 4.7 | 0.30 / 0.65 | 0.59 / 1.5 |
| b + H10 | 1.7 / 4.1 | 58 / 86 | 3.6 / 9.4 | 1.9 / 5.8 | 3.4 / 4.7 | 60 / 87 | 32 / 127 | 3.4 / 4.6 | 0.28 / 0.59 | 0.22 / 0.56 |
| b + H10 + outer | 0.35 / 0.82 | 0.31 / 0.66 | 0.93 / 1.22 | 0.69 / 1.94 | 0.10 / 0.10 | 0.10 / 0.10 | 0.10 / 0.10 | 0.10 / 0.11 | 0.29 / 0.65 | 0.23 / 0.56 |

- Without an ordered outer index (base, h10) no early stop is possible: B stays at 59 / 87 ms (4.5x / 4.9x better than baseline 266 / 428 only because the NOT EXISTS probe replaces the
  GROUP BY aggregate), D 33 / 127 (baseline 38 / 139), A/E 3.4 (baseline 5.3). The H10 index alone does not help the probe: the two status ranges are cheap, the outer side dominates.
- With the status-leading outer index the non-partial schema reaches **H04-class latency (0.1-0.4 ms for variant a, filter off 0.1 ms everywhere including D)** without any partial index
  and with a bound status. Variant a (`IN`) is 2-4x faster than variant b (`<>`, 0.3-1.9 ms on keys on, D 0.93 / 1.22), because `<>` cannot be an index condition on the probe.
- The unrewritten original shape (GROUP BY, no LIMIT) with the status predicate: base/h10 168-208 ms (B 64p, baseline 265), outer 106 / 191 (B), A 2.9 / 4.7, D 29.5 / 130: the head-of-key rewrite with LIMIT is required.
- Generic (generic-serial, `status = $1`): `outer` a: keys on 0.47-1.2 ms (A,B,D) / 2.4 at 256p E, keys off 0.41-0.61, MIN 0.1-1.8; `outer` b: 0.8-3.3 keys on; base/h10: 8.5 / 21 ms (A), B 34 / 627, D 48 / 178 (no ordered path).
- Worst case (`partlist_override=999`, B): non-partial outer 15.5 ms (full scan of the status section, 200k entries), like H04's 15.3.
- Batch 100 / 1000 (B, D; `results/H09n*-<S>/*batch*`): outer index 0.2-3 ms (cost proportional to batch).

**Side note, partial indexes with status predicates** (`H09a`/`H09b`, H04's two indexes, custom-serial 64p / 256p):

| | keys on A | B | D | E | keys off (NOT EXISTS, explicit) A | B | D |
|---|---|---|---|---|---|---|---|
| H09a (`IN`) | **3.4 / 9.1** | 0.19 / 0.29 | 0.08 / 0.09 | **3.4 / 9.3** | 3.1 / 4.1 | 0.14 / 0.12 | 12.2 / 15.2 |
| H09b (`<>`) | 0.16 / 0.25 | 0.19 / 0.33 | 0.09 / 0.09 | 0.28 / 0.55 | 0.11 / 0.12 | 0.13 / 0.13 | 14.3 / 13.9 |
| `_implied` (a / b) | | | | | | B 0.11 / 0.12 | **0.085 / 0.10** |

H09a regresses A/E on keys on (3.4 / 9.1 ms vs H04 0.17 / 0.26: the planner does not choose the partial outer index for the `IN` list there); H09b matches H04 (0.16-0.33). `_implied` is the only form that fixes D
(0.085 ms), but in generic mode it cannot use the partial indexes (33 / 627 ms B, no implication from `status = $1`). The scalar head form with status predicates is as unstable as H05-S (D 10.7 s at 64p for H09a).

**Implication matrix** (`results/H09{a,b}-B/implication.txt`, PG 18): partial index chosen when the query has
| query predicate | a (`IN (NEW,FAILED)`) | b (`<> COMPLETED`) |
|---|---|---|
| literal `status = 'NEW'` | yes | yes |
| literal `status = 'FAILED'` | no (cost: `idx_status` is cheaper) | no (same) |
| literal `status IN ('NEW','FAILED')` | yes | yes |
| literal `status <> 'COMPLETED'` | **no** | yes |
| `status = 'COMPLETED'` (control), `completed_at IS NULL` | no | no |
| generic `status = $1` | no | no |
| generic `status = $1 AND <the index predicate>` | yes | yes |
| custom plan with bound parameter (`status = 'NEW'` after parameter binding) | yes | yes |

Postgres proves `= 'NEW'` => `IN (NEW,FAILED)` and `= 'NEW'` => `<> 'COMPLETED'` (btree operator proof), both from literals / custom plans; for generic plans the explicit predicate in the query text is required.

## Equivalence check: PASS/FAIL + method
Non-partial states, queries a and b, all four states: A, B, D, E x 64/256 (full output `batch=100000000` and `head -10/-100` of the original), keys on, keys off, MIN on, scalar (B only),
and the unrewritten `_filter_orig`: **exact PASS** (12-16 checks per state and scenario, 0 FAIL; `results/H09n{a,b}-{base,h10,outer}-<S>/equivalence.txt`). Partial side note: exact PASS A-E (18/18 per state).
Edge seed (`results/G1i2-edge/equivalence.txt`): every state (partial a/b, non-partial base/h10 a/b) key_ties=0 exact PASS; key_ties=1 PASS-TIES only (same rows, differences inside groups with equal
`MIN(created_at)`), custom + generic, 528 checks in total over all G1-iter2 states, **0 FAIL**. (`outer` state not on the edge seed: same SQL files as base/h10, different index only.)
Equivalence relies on NEW/FAILED <=> `completed_at IS NULL` (see above).

## Regression: other queries whose plan changed; write cost; index size
Write cost (fresh containers, pre-seed DDL, `results/G1i2-wc-{A,B}/`; A: control 26.1 ms insert / 35.1 ms update, WAL 2,438 / 3,318 B/op):
| set | insert ms/1k, WAL B/op | update ms/1k, WAL B/op | index pages after cycle |
|---|---|---|---|
| H09a / H09b (partial, = H04) | 29.1 / 29.4, 2,768 / 2,754 (+13%) | 36.2 / 37.6, 3,310 / 3,318 (0%) | 40,538 |
| H09n (H10 index only, replaces `record_key_created`) | 26.0, 2,512 (+3%) | 34.8, 3,446 (+4%) | 49,994 (+24%) |
| **H09o (H10 + `(status, created_at, id)` INCLUDE)** | 26.8, 2,667 (+9%) | 36.3, **3,720 (+12%)** | 66,135 (+64%) |
(B: same pattern: H09n 2,513 / 3,459; H09o 2,668 / 3,730 B/op.) The partial status indexes cost exactly what H04 costs (same row set). The non-partial outer index adds ~16k pages (130 MB) and
+12% update WAL (every status transition rewrites its entry), the H10 key index +24% index pages (131 MB; the `(record_key, status, created_at, id)` entries are wider than `record_key_created`).
Plans of other queries: not run with the non-partial states (only G1 targets and cleanup were measured); partial side note shares H08's regression (no extra change).

### Experimenter note
Answers: (1) Yes, but only when the query does not repeat the incomplete condition on the same row: `status = :status` alone is estimated correctly (D `earlier` 20k vs 19k actual with the baseline
index; 4.4-5k outer rows with the new indexes) and D keys off drops from 14 ms to 0.09-0.10 ms; keeping a redundant `status IN (...)` / `status <> ...` next to `status = :status` reproduces the
H04 misestimate (432-439 est. vs 19,000 actual). (2) Non-partial: the baseline schema or the H10 key index alone gives only 3-130 ms (no ordered outer index, no early stop); adding a status-leading
non-partial `(status, created_at, id) INCLUDE (...)` outer index gives 0.1-0.4 ms (variant a) / 0.3-1.9 ms (variant b), generic-safe, portable to MySQL/Oracle/SQL Server, for +9% insert / +12% update WAL and 130 MB;
variant b (`<>`) is 2-4x slower than a on non-partial probes but proves implication from `status = 'NEW'` on partial indexes and does not regress A/E where partial-a does. (3) Exact equivalence everywhere, ties only with key_ties=1.
The H10 key index does serve both G1 (probe as two status ranges) and G2, but the G1 gain only appears together with an ordered outer index.

## Decision + reasoning



## Orchestrator decision
measured → portable variant. misestimate disappears only when `status = :status` stands alone (D off 14→0.09); stacking IN/<> keeps it. Candidate for MySQL/MariaDB/Oracle/MSSQL set (ledger: `../STATE.md`; final report: `../99-final-report.md`)
