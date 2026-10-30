# H02: Partial index on incomplete records for the predecessor probe
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on), `findOldestDueNextRetryAtByPartitions` (filter on);
side benefit expected for `findIncompleteRecordPages` / `findIncompleteRecordsByRecordKey` (G2, Phase 2).
Builds on: H01 (same SQL).

## Change

DDL (`experiments/H02/indexes.sql`):

```sql
CREATE INDEX idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
```

SQL: the H01 rewrite (`older.created_at <= o.created_at` added). Harness files: `experiments/H02/queries/`
(identical to `experiments/H01/queries/`).

The query text already contains `older.completed_at IS NULL` literally, so the partial predicate is provable in
custom **and** generic plans (no parameter is involved), unlike a `WHERE status = 'NEW'` predicate, which would
only match custom plans with the literal status.

## Rationale / expected plan

The anti join's inner side only ever needs incomplete rows (NEW + FAILED). The partial index holds exactly those
(A 6.2k entries, B 200k, D 20k: < 1 MB in A, ~15 MB in B) instead of the 1M-entry
`record_key_completed_created` (68 MB), and its key order `(record_key, created_at, id)` is the per-key order of
the contract, so with the H01 bound a probe is
`Index Only Scan using idx_outbox_record_incomplete_key: Index Cond (record_key = o.record_key AND created_at <= o.created_at)`,
`Filter: OR(...)`, stopping at the first entry. Two plan shapes become cheap, whichever the planner picks:

- `Nested Loop Anti Join` (outer: due NEW rows; inner: index-only probe, ~2-3 buffers);
- `Hash Anti Join` whose inner side is an `Index Only Scan` of the small partial index instead of the
  89k-buffer parallel seq scan of the heap (B baseline) or the bitmap heap scan (D baseline).

Expected (serial, 64p / 256p):
- B: 265 / 367 ms -> ~60-90 / ~80-120 ms. The remaining cost is the outer side (all 200k NEW via `idx_status`,
  partitions as filter: the no-filter query costs 56 / 76 ms for exactly that); H03 addresses it.
- C: 132 / 208 -> ~40 / ~80 ms (same outer problem).
- D: 38 / 141 -> ~3-5 / ~6-10 ms (quadratic join filter gone).
- A/E: 5.0 / 19 -> ~2-3 / ~5-8 ms.
- MIN query (filter): same proportions (B 165 / 214 ms with workers).

Index-only probes depend on the visibility map. Incomplete rows are recent, so in production their pages are
often not all-visible: expect heap fetches; the experimenter measures both (step 5).

## Semantic equivalence argument

Index-only change plus the H01 predicate (equivalent, see H01). The partial predicate equals the query's
`older.completed_at IS NULL`, so no row the query needs is missing from the index.

## Portability

- postgres: partial index as above. `CREATE INDEX CONCURRENTLY` for existing installs.
- sqlserver: filtered index `CREATE INDEX ... ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;`
  is valid; SQL Server matches filtered indexes in parameterized plans only when the filter column predicate is a
  literal in the query (`completed_at IS NULL` is), so it works.
- mysql / mariadb: no partial indexes. Equivalent effect from the existing `(record_key, completed_at, created_at)`
  with the H01 bound (InnoDB appends `id`). Do not add this index there.
- oracle: no partial indexes. Oracle omits an entry only when *all* key columns are NULL, so the function-based
  trick needs every column wrapped: `(CASE WHEN completed_at IS NULL THEN record_key END, CASE WHEN completed_at IS NULL THEN created_at END, ...)`,
  and the query must use the same expressions (dialect-specific SQL). Not worth it: keep the existing composite
  index + H01 on Oracle.
- jpa: `@Table(indexes)` cannot express a partial index; add it in the schema scripts only (JPA users relying on
  `ddl-auto` get the non-partial index set). JPQL needs only the H01 predicate.
- mongo: a partial index `{recordKey: 1, createdAt: 1, _id: 1}` needs a `partialFilterExpression` on
  `completedAt` being null/absent; which operators are accepted for that (null equality vs `$exists: false`) and how
  the entity stores a null `completedAt` must be checked in Phase 3.

Consolidation note (Phase 2): if accepted, this index can replace `idx_outbox_record_record_key_completed_created`
for every incomplete-row lookup (`findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey`), and
`idx_outbox_record_record_key_created` is already a drop candidate (baseline). Replacing both would cut write cost
(partial: no entry for the NEW -> COMPLETED update; non-partial: one entry per update).

## Experiment plan

1. Scenarios A, B, C, D, E; parts 64 and 256.
   `./run-suite.sh H02-<S> <S> --ddl ../experiments/H02/indexes.sql -q ../experiments/H02/queries --skip-writes --keep`
2. Report `serial` (key query) and `custom` (MIN query) against baseline, plus which anti-join shape was chosen
   and whether the inner side is `Index Only Scan` (Heap Fetches).
3. Generic plan (`generic`, `generic-serial`): the partial predicate is constant, so the index must be usable in
   the generic plan too; confirm.
4. Equivalence: as H01 step 4 (edge, edge with `key_ties=1`, each scenario, parts 64/256), both queries.
5. Visibility-map sensitivity on B: on the kept container
   `ALTER TABLE outbox_record SET (autovacuum_enabled = off); UPDATE outbox_record SET failure_count = failure_count WHERE completed_at IS NULL;`
   (no VACUUM), then rerun `run-explain.sh -c outbox-db-H02-B -s H02-B -q ../experiments/H02/queries --set max_parallel_workers=0 --tag serial-novm findRecordKeysInPartitions_filter`.
   Do the same on a baseline container for a fair comparison if the numbers differ a lot.
6. Regression (index added): full suite without `-q` and with writes:
   `./run-suite.sh H02-reg-<S> <S> --ddl ../experiments/H02/indexes.sql` for A, B, D (plan flips in
   `findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey`, `deleteByRecordKeyAndStatus`), write cost and size
   (`sizes.txt`, also after `workload-mix.sh` to see churn/bloat of the partial index).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 1, shared by H01–H04): one container per scenario (`outbox-db-H01`, 1M rows, C 200k), index
states applied **in place after seeding** (`CREATE INDEX` + `VACUUM ANALYZE`): control (repo indexes, original SQL,
`results/G1ctl-<S>/`) → H01 SQL → +H02 index → +H03 index → H04 (H03 partition index dropped, H04 created index
added) → H03b (B, D). Driver: `experiments/G1-iter1/run-scenario.sh`; query dirs = `harness/queries` overlaid with
the H files (`make-qdirs.sh`). Per state: target queries in `custom-serial` (pgjdbc `withMaxRows` execution, the
app's real mode for the key queries), `generic-serial`, `custom` (with workers; the MIN query has no `withMaxRows`,
so this is its real mode) and `generic`; 1 warm-up + 3 runs, median; buffers = shared hits. "ctl" numbers come from
the same container before any change (they match `results/base-<S>` within noise). Tables: ms (shared hits),
ctl → hypothesis; "keys on/off" = `findRecordKeysInPartitions` filter on/off, "MIN on/off" =
`findOldestDueNextRetryAtByPartitions` filter on/off. Raw: `results/<H>-<S>/`. All data pages all-visible (fresh
VACUUM; Heap Fetches ≈ 0): the visibility-map sensitivity run (H02 step 5) was not done.

**custom-serial** — ms (shared hits), ctl → H02

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.27 (7,374) → 3.42 (586) | 266 (106,225) → 148 (68,614) | 132 (33,368) → 100 (18,773) | 38.1 (7,273) → 29.4 (2,068) | 5.71 (7,287) → 3.36 (588) |
| keys on | 256 | 20.3 (27,806) → 5.24 (577) | 428 (106,225) → 248 (68,920) | 208 (33,368) → 184 (18,773) | 139 (7,273) → 122 (2,068) | 19.9 (26,509) → 5.22 (578) |
| MIN on | 64 | 4.89 (7,374) → 3.1 (577) | 267 (106,225) → 144 (68,920) | 124 (33,368) → 93.5 (18,773) | 37.1 (7,273) → 29.6 (2,068) | 5.24 (7,287) → 3.28 (588) |
| MIN on | 256 | 17.6 (27,806) → 4.19 (577) | 364 (106,225) → 240 (68,920) | 178 (33,368) → 159 (18,773) | 139 (7,273) → 122 (2,068) | 18.3 (26,509) → 4.63 (578) |

**generic-serial** — ms (shared hits), ctl → H02

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 10.5 (7,374) → 8.93 (577) | 234 (106,009) → 129 (164,169) | 107 (33,118) → 122 (166,327) | 54.9 (7,273) → 45.8 (2,068) | 10.9 (7,287) → 8.78 (578) |
| keys on | 256 | 49.6 (9,678) → 24.3 (577) | 915 (106,225) → 761 (68,920) | 787 (34,088) → 998 (616,205) | 197 (7,273) → 181 (2,068) | 50.6 (9,693) → 24.4 (578) |
| MIN on | 64 | 10 (7,374) → 8.32 (577) | 228 (106,009) → 124 (164,169) | 88.9 (33,118) → 115 (166,327) | 55.2 (7,273) → 46.6 (2,068) | 11.7 (7,287) → 8.38 (578) |
| MIN on | 256 | 49.4 (9,678) → 21.9 (577) | 863 (106,225) → 748 (68,920) | 761 (34,088) → 964 (616,205) | 198 (7,273) → 180 (2,068) | 48.8 (9,693) → 22.6 (578) |

**custom** — ms (shared hits), ctl → H02

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.31 (7,374) → 3.4 (586) | 169 (106,367) → 90.2 (69,022) | 63.5 (33,368) → 51.5 (18,782) | 38 (7,273) → 30.2 (2,068) | 5.53 (7,287) → 3.37 (588) |
| keys on | 256 | 15.9 (27,864) → 5.57 (577) | 237 (106,372) → 248 (68,920) | 102 (33,368) → 94.6 (18,775) | 140 (7,273) → 125 (2,068) | 15.6 (26,567) → 5.2 (578) |
| MIN on | 64 | 4.88 (7,374) → 3.17 (577) | 162 (106,369) → 88.3 (69,016) | 62.8 (33,368) → 48.3 (18,782) | 37.9 (7,273) → 30.4 (2,068) | 5.18 (7,287) → 3.24 (588) |
| MIN on | 256 | 15.4 (27,848) → 4.26 (577) | 211 (106,370) → 139 (69,018) | 85.5 (33,368) → 71.9 (18,775) | 139 (7,273) → 124 (2,068) | 15.6 (26,551) → 4.33 (578) |

Plan shapes (custom-serial, key query filter on): the inner side is always `Index Only Scan using
idx_outbox_record_incomplete_key` (Heap Fetches 0), but the join shape depends on the scenario:
- A/E: 64p merge anti join, 256p hash anti join over the whole partial index (6.2k entries): 5.3 → 3.4 ms (64p),
  20 → 5.2 ms (256p); buffers 7.4k/28k → ~580.
- B: (parallel-capable) hash anti join, inner = full index-only scan of the 200k-entry partial index (52k buffers
  instead of the 89k-buffer heap seq scan), hash spills 4 batches; outer side unchanged (`idx_status`, partition as
  filter, 150k rows removed). 266 → 148 ms (64p), 428 → 248 ms (256p): −44% / −42%.
- C: 132 → 100 / 208 → 184 ms (same shape as B).
- D: hash anti join, the join filter still removes 358k (64p) / 1.9M (256p) rows; −23% / −12%.
- No nested-loop anti join is chosen in B/C/D, so the H01 bound never becomes an index condition there.

Generic (`generic-serial`): index used in the generic plan too (the partial predicate is a literal). B 234 → 129 ms
(64p), 915 → 761 ms (256p, still the 256-param bad plan: parallel-capable index scan of `idx_status`); A 50 → 24 ms
(256p). Never worse than control.

## Equivalence check: PASS/FAIL + method
**PASS.** Scenarios A–E × parts 64/256 × both filter queries: exact PASS (20/20). Edge seed: exact PASS everywhere
except 1 PASS-TIES (key_ties=1, generic, 64p), plan-dependent tie order of the original.
Method: `experiments/G1-iter1/eq-check.sh` (ordered output of original vs rewritten via psql; on mismatch a tie
analysis maps every returned key to its sort value `MIN(created_at)` of its due NEW rows: identical row multiset +
identical sort-value sequence = **PASS-TIES**, i.e. the only difference is the order among keys with equal
`MIN(created_at)`, which the original query leaves to the plan). Rewritten H04 key queries run with
`batch=100000000` for the full comparison.
- Scenarios A, B, C, D, E (index state of the hypothesis, custom plans), parts 64 and 256: `results/<H>-<S>/equivalence.txt`.
- Edge seed (`seed-edge.sql`: FAILED heads, FAILED/NEW `created_at` ties both id orders, mixed `next_retry_at`,
  history + COMPLETED after NEW, 128 empty partitions), with and without `key_ties=1`, custom **and** generic plans,
  parts 64/256: `results/G1-edge/equivalence.txt` (driver `run-edge.sh`).
- With `key_ties=1` the original query itself returns tied keys in plan-dependent order: H01 (identical predicate set)
  and H02 (index only) already show PASS-TIES vs the original in 2 of 16 checks (generic plans / parallel 256p plans).

## Regression: other queries whose plan changed; write cost; index size
Full baseline suite (all queries, custom / generic / serial / generic-serial, baseline SQL) with the index applied
**post-seed** on the measurement container (`results/H02-reg-{A,B}/`), compared with `results/base-{A,B}/`
(`experiments/G1-iter1/reg-compare.py`; flags = plan shape or index change, or >10% and >0.2 ms slower):
- Plan changes: only `findRecordKeysInPartitions_filter` and `findOldestDueNextRetryAtByPartitions_filter` (the
  original SQL), which switch their inner side to `incomplete_key` and get faster (A 256p 19.2 → 5.2 ms serial,
  B 64p 265 → 149 ms serial, B 256p 367 → 256 ms). `findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey`,
  `deleteByRecordKeyAndStatus` keep their plans (the partial index is not used: their SQL has no `completed_at IS NULL`).
- Slower >10% with an unchanged plan (judged noise, different container): A `findOldestCreatedAtByPartitions` 256p
  generic 11.1 → 13.0 ms; B `deleteCompletedRecords_selectIds` serial 159 → 179 ms (seq scan + sort, unrelated).

Write cost (`results/G1-wc-{A,B}/{base,H02}.txt`; fresh container per index set, index created **before** seeding,
50k INSERT + 50k `updateProcessingState` NEW→COMPLETED via pgbench, same session as the control):

| | insert ms/1k | insert WAL B/op | update ms/1k | update WAL B/op |
|---|---|---|---|---|
| A control → H02 | 25.9 → 27.8 (+7%) | 2449 → 2618 (+7%) | 36.1 → 36.3 (+1%) | 3307 → 3307 (0%) |
| B control → H02 | 26.1 → 27.4 (+5%) | 2436 → 2605 (+7%) | 35.1 → 36.9 (+5%) | 3328 → 3326 (0%) |

The update to COMPLETED adds no entry (row leaves the partial predicate), so update WAL is unchanged.
Index size (incremental build): A 89 pages (0.7 MB), B 2,359 pages (18 MB), C/D proportionally to incomplete rows
(post-seed build: B 16 MB). Entries of completed rows stay until VACUUM: after the 50k insert + complete cycle (no vacuum in between) A grows 89 → 746 pages (5.8 MB), i.e. the partial index churns with throughput, not with backlog.

### Experimenter note
H02 is a real but partial win: the partial index makes the anti join's inner side cheap in every plan shape (and it
is picked up by the *unchanged* SQL, also in generic plans), giving −40 to −45% in B and −60 to −75% in A/E at
256 partitions, for ~+7% insert WAL and no update cost. It does not reach the 6.1 target alone: B/C remain dominated
by the outer side (all NEW rows via `idx_status`, partition as filter) plus HashAggregate + Sort of all keys, and D
keeps a quadratic join filter because the planner never picks a nested loop there. Good building block (H03/H04 rely
on it), not a solution by itself.

## Decision + reasoning
_pending_


## Orchestrator decision
measured → component. kept as building block (H04, H12); not sufficient alone (B −44%) (ledger: `../STATE.md`; final report: `../99-final-report.md`)
