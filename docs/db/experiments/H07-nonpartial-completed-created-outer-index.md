# H07: Portable non-partial outer index `(completed_at, created_at, id)` instead of the partial one
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on/off). `findOldestDueNextRetryAtByPartitions` keeps H04's form.
Builds on: H06 (probes on the existing key index, no partial index). Iteration 2 of G1, addresses issue (c);
measured on Postgres as the proxy for MySQL/MariaDB/Oracle, which have no partial indexes.

## Change

DDL, H07a lean (`experiments/H07/indexes.sql`), H07b covering (`experiments/H07/indexes-covering.sql`):

```sql
-- H07a
CREATE INDEX idx_outbox_record_completed_created ON outbox_record (completed_at, created_at, id);
-- H07b
CREATE INDEX idx_outbox_record_completed_created ON outbox_record (completed_at, created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key);
-- both: no partial index (incomplete_created / incomplete_key dropped)
```

SQL (`experiments/H07/queries/`): H06 queries with the **outer** order changed to

```sql
ORDER BY o.completed_at ASC, o.created_at ASC, o.id ASC
LIMIT :batchSize
```

(filter on: H04 NOT EXISTS probe; filter off: H06 scalar head subquery; MIN filter on: H04 unchanged.)

## Rationale / expected plan

Incomplete rows are the contiguous `completed_at IS NULL` section of a `(completed_at, ...)` index, stored in
`(created_at, id)` order, so `Index Cond: completed_at IS NULL` + ordered scan + `Limit` gives the same early stop as
the partial index, on any database that indexes NULLs. Postgres derives pathkeys only from equalities, not from
`IS NULL`, so it would not see that the section is ordered by `created_at` and would sort; ordering by
`o.completed_at` first (constant NULL in the result) makes the query order equal to the index order.

Expected plan: `Limit -> Nested Loop Anti Join -> Index Scan using idx_outbox_record_completed_created o
(Index Cond: completed_at IS NULL; Filter: partition_no = ANY, status, next_retry_at)` + probe on
`record_key_completed_created`. H07a needs a heap visit per scanned outer entry (B ~60 for batch 10, fine); H07b is
index-only like H04 (visibility map permitting).

| | H04/H06 partial | H07a | H07b |
|---|---|---|---|
| entries | incomplete rows only (B 200k) | all rows (1M) | all rows |
| est. size B | 20 MB | ~70–75 MB | ~105–115 MB |
| entry on insert | yes | yes | yes |
| entry on completing update | **no** | yes (non-HOT update, key changes) | yes |
| outer scan B, batch 10 | ~50 entries, index-only | ~60 heap visits, ~0.3–0.5 ms | ≈ H06 |
| worst case B (no due head owned) | 200k entries, 15 ms | 200k heap visits, ~40–80 ms | ~15–20 ms |

So on Postgres H07 is expected to **lose** against the partial index on write cost (one more index entry per update:
+3–6% update WAL) and size, and H07a also on the worst case. The value of the measurement is (1) the numbers
Phase 3 needs to judge the MySQL/MariaDB/Oracle set, (2) whether a single, non-partial definition is acceptable on
Postgres too (simpler schema, JPA `@Table(indexes)` can express it), and (3) a cross-group reuse: the non-NULL part of
the same index is ordered by `completed_at`, which is exactly what G5's cleanup selects need
(`status = 'COMPLETED' AND completed_at < :cutoff ORDER BY completed_at`, H40 proposes a separate
`(status, completed_at)`). If the planner uses H07's index for them, one index serves G1 and G5 on every dialect.

Planner risk: the outer estimate has the same independence error as H04 (`completed_at IS NULL` × `status = 'NEW'`,
D: 82 estimated); with a non-partial index the planner could prefer `idx_status` + top-N sort when it thinks few rows
match. Check `--costs`.

Not proposed: using H07's index to replace `idx_outbox_record_status` (would need `countByStatus(COMPLETED)` and the
NEW/FAILED lookups rewritten to `completed_at IS [NOT] NULL`, i.e. G4 query changes resting on the lifecycle
invariant; left to Phase 2).

## Semantic equivalence argument

H06 queries, plus `o.completed_at` as the leading outer sort key. Every returned row satisfies
`o.completed_at IS NULL`, so all rows tie on it and the order (and every LIMIT prefix) is decided by
`(created_at, id)` exactly as in H04/H06.

## Portability

- postgres: as written; NULLS LAST matches `ORDER BY completed_at ASC` (default NULLS LAST for ASC).
- mysql/mariadb (InnoDB): `(completed_at, created_at)` already ends in the PK `id` implicitly; covering version =
  trailing key columns `(completed_at, created_at, id, partition_no, status, next_retry_at, record_key)` (no
  INCLUDE); `completed_at IS NULL` is a `ref` access, `ORDER BY completed_at, created_at, id` without filesort; index
  condition pushdown evaluates partition/status/retry before the clustered lookup when they are in the index.
  `LIMIT ?`.
- sqlserver: prefer the filtered index (H04/H06, `WHERE completed_at IS NULL` + INCLUDE); if the schema is to stay
  uniform, H07 works (NULLs sort first, the section is still contiguous). `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY`.
- oracle: rows whose indexed columns are all NULL are not indexed, but `created_at` is NOT NULL, so every row is in the
  index and `completed_at IS NULL` is an index range (NULLs last in ascending indexes). No INCLUDE: trailing key
  columns for covering. `FETCH FIRST ? ROWS ONLY`.
- jpa: `@Index(columnList = "completedAt, createdAt, id")` expresses H07a exactly (partial indexes cannot be).
- mongo: `{completedAt: 1, createdAt: 1, _id: 1}`; `{completedAt: null}` matches missing and null fields and can use
  the index bounds `[null, null]` (Phase 3: check the pipeline puts `$match` + `$sort` before `$group`).

## Experiment plan

Continue in place after H06/H08a (or fresh container): drop `incomplete_created`, create H07a, `VACUUM ANALYZE`;
later H07b.
1. A, B, D (C, E optional) × parts 64/256: custom-serial, generic-serial, `--costs` for the two key queries
   (H07 query dir). Report the outer index chosen and heap fetches. B batch 10 / 1000.
2. Worst case `partlist_override=999` on B (H07a vs H07b vs H04 15.3 ms).
3. G5 reuse: on A and B run `findCompletedRecordsBefore`, `deleteCompletedRecords_selectIds` (baseline SQL, serial,
   cutoff from the harness) and, if G5 has an H41 limit file, that one too: does the planner use
   `idx_outbox_record_completed_created`? ms vs baseline (157–273 ms) and vs H40.
4. Equivalence: H07 key queries vs original, A–E × 64/256 full output + edge seed (as H05).
5. Write cost (pre-seed DDL, A and B): H07a and H07b vs control/H06 (insert + update, WAL); index sizes after seed
   and after the 50k write cycle.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 2, `experiments/G1-iter2/run-scenario.sh`, tables `results/G1i2-tables.md`, logs `results/logs-iter2/`): one container per
scenario (A, B, C, D, E; `outbox-db-H05..H09`, tmpfs data dir, 1M rows, C 200k), index states applied in place after seeding with
`VACUUM ANALYZE` (fresh VM). Timing: 1 warm-up + 3 runs, median, `custom-serial` = `max_parallel_workers=0` (pgjdbc `withMaxRows`),
`generic-serial` = force_generic_plan + no workers, `--costs` (estimate vs actual), worst case `partlist_override=999`, batch 100/1000 on B, D.
Reference numbers for the iteration-1 states (control, H04) come from `results/G1ctl-<S>` and `results/H04-<S>`; the H04 filter-off file was re-run
on the H04 state (`results/H05ref-<S>`) and reproduces them (D 14.0/14.3 ms, others 0.11-0.16 ms). Numbers: ms (serial custom, parts 64 / 256).

Outer index `(completed_at, created_at, id)` (H07a lean 73 MB, H07b covering 117 MB), probe on the existing key index, outer `ORDER BY completed_at, created_at, id`.
Keys on custom-serial 64p / 256p, H04 (partial) -> H07a -> H07b:

| | A | B | C | D | E |
|---|---|---|---|---|---|
| H04 | 0.17/0.26 | 0.20/0.36 | 0.18/0.29 | 0.09/0.09 | 0.27/0.61 |
| H07a | **4.92 / 14.9** | 0.24 / 0.35 | 0.23 / 0.38 | 0.09 / 0.11 | **4.92 / 15.4** |
| H07b | 0.22 / 0.39 | 0.21 / 0.40 | 0.20 / 0.35 | 0.09 / 0.12 | 0.39 / 0.90 |

- H07a (lean): heap visits for `partition_no/status/next_retry_at` make A/E (many not-due and FAILED rows ahead of the first hit) cost 4.9 / 15 ms, i.e. equal to the control at 64p (5.3 / 20.3 ms in A) and
  -27% at 256p: no real gain in A/E. B/C/D unaffected. H07b (covering) restores 0.2-0.9 ms but is 117 MB (+60% over lean).
- Filter-off (H06 scalar form with the new ORDER BY): same instability as H05/H06 (B 212 ms, D 399 / 2,399 ms for H07a).
- Worst case B: filter on **0.061 ms** (!): non-partial index starts at the NULL section, the planner sees the empty partition. MIN on unchanged (0.15-0.35).
- **Cleanup selects (G5 reuse)** served by the new index (baseline SQL, serial custom): `findCompletedRecordsBefore` A 281 -> 21.3 (H07a) / 20.6 (H07b), B 217 -> 15.1 / 15.7;
  `deleteCompletedRecords_selectIds` A 171 -> 52.8 / **12.6 (H07b, index-only)**, B 163 -> 42.1 / 9.6, D 50.0, E 51.0 (H07a). `findFailedRecordsBefore` unchanged (0.11, status index).
- ORDER BY: Postgres needs `completed_at` as leading sort key to use the non-partial index (H08 step 4 below).

## Equivalence check: PASS/FAIL + method
Key files vs the original: exact PASS on A-E x 64/256 (10/10 per scenario for the files present), edge seed key_ties 0/1 custom + generic: PASS / PASS-TIES (key_ties=1 only), no FAIL.
Cleanup selects were only timed, not compared (same SQL as the baseline).

## Regression: other queries whose plan changed; write cost; index size
Write cost (A; `results/G1i2-wc-A`): H07a insert 26.3 ms/1k, WAL 2,563 B/op (+5%); update 36.6 ms/1k, WAL **3,490 B/op (+5%)**; H07b 2,596 (+6%) / 3,571 (+8%).
Updates now touch an indexed column (`completed_at` NULL -> value): the index entry moves. Index pages after the cycle: A 51,173 (H07a) / 57,695 (H07b) vs control 40,368.

### Experimenter note
Portable stand-in works only with covering columns for A/E-type data (H07b 117 MB, +8% update WAL), but it additionally speeds the cleanup selects 10-20x
(281 -> 13-21 ms) which makes it attractive for G5. The lean variant loses the early-stop advantage in A/E (4.9 / 15 ms, equal to baseline at 64p). Filter-off needs a different fix (H09).

## Decision + reasoning

_pending_


## Orchestrator decision
rejected for PG. side finding: cleanup select B 217→15 ms — compare with H40 (ledger: `../STATE.md`; final report: `../99-final-report.md`)
