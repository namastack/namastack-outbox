# H03: Covering partial index on incomplete records by partition (outer side), plus H02
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on and off), `findOldestDueNextRetryAtByPartitions` (filter on);
also watch `findOldestCreatedAtByPartitions`, `countRecordsByPartitions` (G4) in the regression run.
Builds on: H01 (SQL bound) + H02 (probe index).

## Change

DDL (`experiments/H03/indexes.sql`):

```sql
CREATE INDEX idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;                                   -- H02
CREATE INDEX idx_outbox_record_incomplete_partition
    ON outbox_record (partition_no, status, next_retry_at)
    INCLUDE (record_key, created_at, id)
    WHERE completed_at IS NULL;
```

SQL: add `AND o.completed_at IS NULL` to the outer `WHERE` of the three target queries (the filter queries keep the
H01 bound). Key query, filter on:

```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND o.completed_at IS NULL                                     -- added (H03)
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at                      -- H01
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC
```

Key query, filter off: the current SQL plus `AND o.completed_at IS NULL`. MIN query (filter on): the H01 SQL plus
`AND o.completed_at IS NULL`. MIN query (filter off) is unchanged (0.04 ms via `status_retry` + `Limit`).

Harness files: `experiments/H03/queries/{findRecordKeysInPartitions_filter,findRecordKeysInPartitions_nofilter,findOldestDueNextRetryAtByPartitions_filter}.sql`.

Secondary variant H03b (`experiments/H03/indexes-nonpartial.sql`): replace `idx_outbox_record_partition_status_retry`
by the same key columns `INCLUDE (record_key, created_at, id)`, non-partial, and run the **baseline** query files
(no `o.completed_at IS NULL` needed). Measures what a portable, query-neutral covering index gives.

## Rationale / expected plan

The outer side is now the dominant cost: the planner reads every NEW row of the table through `idx_status` and
applies `partition_no = ANY(...)` as a heap filter (B: 200k heap rows fetched, 150k removed, 16.8k buffers, 56 ms
alone in the no-filter query). The existing `partition_status_retry (partition_no, status, next_retry_at)` could
restrict to owned partitions but still needs a heap fetch per row for `record_key`/`created_at`/`id`, so the planner
does not prefer it at 64 partitions.

The partial covering index makes the outer side an
`Index Only Scan using idx_outbox_record_incomplete_partition`, `Index Cond: partition_no = ANY(...) AND status = 'NEW' AND next_retry_at <= now`
(64 or 256 descents; Postgres 18 handles the SAOP on the leading column natively), returning exactly the due rows of
owned partitions with all columns the anti join, `GROUP BY` and `ORDER BY` need, no heap access when pages are
all-visible. Combined with H02's probe, total work is proportional to due rows in owned partitions (target 6.1).

Expected (serial, 64p / 256p):
- filter on, B: 265 / 367 -> ~15-30 / ~50-90 ms (49k / 200k outer entries, then H02 probes or a hash over the small
  partial index; HashAggregate + Sort of 6k / 25k keys remain, the app still only uses 10).
- filter on, C: 132 / 208 -> ~10-20 / ~40-70 ms. D: ~2-4 ms. A/E: ~1-2 ms.
- filter off, B: 56 / 76 -> ~10-15 / ~35-45 ms (index-only scan + HashAggregate + Sort).
- MIN filter on: same order as the filter key query (no sort).

Why not just `WHERE status = 'NEW'` as the partial predicate: it would match only custom plans with the literal
status; `completed_at IS NULL` is a constant in the SQL and matches generic plans too. Why `INCLUDE` and not more key
columns: `record_key, created_at, id` are only output/correlation columns; keeping `(partition_no, status, next_retry_at)`
as key columns keeps the index range exact.

## Semantic equivalence argument

`o.completed_at IS NULL` is implied by `o.status = 'NEW'` under the record lifecycle invariant: `completedAt` is set
only together with `COMPLETED` (`OutboxRecord.markCompleted`; `OutboxRecordProcessor.completeRecord` restores both on
failure; records are created with `completedAt = null`; `markFailed` keeps it null). So for `status` in {NEW, FAILED}
the added conjunct removes nothing and results/order are identical. The only caller (`OutboxProcessingScheduler`,
`OutboxMetricsCollector`) passes `NEW`. For `status = COMPLETED` the rewritten query would return nothing; if that
SPI use must be preserved, the repository can append the conjunct only when `status != COMPLETED`, or the KDoc can
state that `status` is an incomplete status. Rows written by external tools with `status = 'NEW'` and a non-null
`completed_at` would be excluded (they are also inconsistent for `findIncompleteRecordPages`, which already filters on
`completed_at IS NULL`).

## Portability

- postgres: as above (`INCLUDE` since PG 11).
- sqlserver: filtered index with `INCLUDE` is native:
  `CREATE INDEX ... ON outbox_record (partition_no, status, next_retry_at) INCLUDE (record_key, created_at, id) WHERE completed_at IS NULL`.
- mysql / mariadb: no partial index, no `INCLUDE`. Equivalent: widen `idx_outbox_record_partition_status_retry` to
  `(partition_no, status, next_retry_at, record_key, created_at)` (InnoDB appends `id`), i.e. H03b; the outer
  `completed_at IS NULL` conjunct is then a cheap row filter. Test in Phase 3.
- oracle: no partial index, no `INCLUDE`; same widened composite index as MySQL (covering via index fast full /
  range scan).
- jpa: entity `@Table(indexes)` can only express the non-partial widened variant (H03b columns); JPQL gets
  `and o.completedAt is null`.
- mongo: `partition_status_retry_idx` already exists; covering needs `recordKey, createdAt` appended
  (`fifo_pipeline_idx {partitionNo, recordKey, createdAt}` may already serve the pipeline); Phase 3.

## Experiment plan

1. Scenarios A, B, C, D, E; parts 64 and 256.
   `./run-suite.sh H03-<S> <S> --ddl ../experiments/H03/indexes.sql -q ../experiments/H03/queries --skip-writes --keep`
2. Report `serial` (key queries) and `custom` (MIN) vs baseline and vs H02; confirm `Index Only Scan` on the outer
   side with `partition_no` as index condition, `Heap Fetches`, and the remaining HashAggregate/Sort cost.
3. Generic plan: partial predicate is constant -> must stay usable; compare `generic-serial` with baseline 872 ms
   (B/256). With 64/256 parameters for `partition_no`, check that the generic estimate does not flip to `idx_status`.
4. Equivalence: H01 step 4 procedure for the three files (filter and no-filter key query, MIN filter), edge seed
   (with and without `key_ties=1`) and every scenario, parts 64/256.
5. Visibility-map sensitivity on B: as H02 step 5 (`serial-novm` tag).
6. H03b: `./run-suite.sh H03b-B B --ddl ../experiments/H03/indexes-nonpartial.sql -q ../harness/queries --skip-writes`
   restricted to the G1 files if possible (or full suite), B and D only.
7. Regression (two new indexes): full suite with writes, `./run-suite.sh H03-reg-<S> <S> --ddl ../experiments/H03/indexes.sql`
   for A, B, D. Watch `findOldestCreatedAtByPartitions`, `countRecordsByPartitions` (planner may now pick the new
   index: they do not contain `completed_at IS NULL`, so it cannot, unless rewritten in G4), `findIncompleteRecordPages`,
   `findPendingRecords`; write cost (WAL/op) and sizes before/after `workload-mix.sh`.

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

H03 (both partial indexes, H03 SQL with outer `o.completed_at IS NULL`):

**custom-serial** — ms (shared hits), ctl → H03

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.27 (7,374) → 2.99 (2,994) | 266 (106,225) → 108 (148,310) | 132 (33,368) → 59.8 (2,727) | 38.1 (7,273) → 13.4 (10,256) | 5.71 (7,287) → 2.95 (2,995) |
| keys on | 256 | 20.3 (27,806) → 11.2 (11,482) | 428 (106,225) → 276 (99,972) | 208 (33,368) → 205 (4,627) | 139 (7,273) → 67.8 (57,266) | 19.9 (26,509) → 10.8 (11,486) |
| keys off | 64 | 1.43 (510) → 1.39 (23) | 55.6 (16,832) → 11.4 (757) | 29.5 (16,434) → 13.7 (638) | 4.66 (1,858) → 0.708 (55) | 1.49 (509) → 1.36 (24) |
| keys off | 256 | 2.83 (510) → 5.54 (81) | 73.8 (16,832) → 45.5 (3,050) | 75.4 (16,684) → 54.4 (2,538) | 5.88 (1,858) → 3.52 (265) | 2.42 (509) → 5.66 (85) |
| MIN on | 64 | 4.89 (7,374) → 2.45 (2,994) | 267 (106,225) → 105 (148,310) | 124 (33,368) → 55.6 (2,727) | 37.1 (7,273) → 13.2 (10,256) | 5.24 (7,287) → 2.46 (2,995) |
| MIN on | 256 | 17.6 (27,806) → 9.26 (11,482) | 364 (106,225) → 270 (99,963) | 178 (33,368) → 158 (18,773) | 139 (7,273) → 66.7 (57,266) | 18.3 (26,509) → 9.23 (11,486) |

**generic-serial** — ms (shared hits), ctl → H03

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 10.5 (7,374) → 2.75 (2,994) | 234 (106,009) → 107 (148,310) | 107 (33,118) → 105 (150,531) | 54.9 (7,273) → 13.5 (10,256) | 10.9 (7,287) → 2.8 (2,995) |
| keys on | 256 | 49.6 (9,678) → 7.54 (186) | 915 (106,225) → 276 (99,972) | 787 (34,088) → 236 (4,627) | 197 (7,273) → 120 (475) | 50.6 (9,693) → 7.38 (187) |
| keys off | 64 | 6.76 (510) → 0.639 (23) | 26.7 (16,616) → 11.3 (757) | 29.8 (16,434) → 13.7 (638) | 20.7 (1,858) → 0.811 (55) | 6.68 (509) → 0.662 (24) |
| keys off | 256 | 19.6 (510) → 2.24 (81) | 621 (16,832) → 45.5 (3,050) | 639 (17,404) → 55.1 (2,538) | 61.8 (1,858) → 3.68 (265) | 19.5 (509) → 2.23 (85) |
| MIN on | 64 | 10 (7,374) → 2.55 (2,994) | 228 (106,009) → 104 (148,310) | 88.9 (33,118) → 98.1 (150,531) | 55.2 (7,273) → 13.3 (10,256) | 11.7 (7,287) → 2.47 (2,995) |
| MIN on | 256 | 49.4 (9,678) → 7.11 (186) | 863 (106,225) → 268 (99,963) | 761 (34,088) → 212 (4,627) | 198 (7,273) → 120 (475) | 48.8 (9,693) → 6.77 (187) |

**custom** — ms (shared hits), ctl → H03

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.31 (7,374) → 2.98 (2,994) | 169 (106,367) → 107 (148,310) | 63.5 (33,368) → 63.8 (2,727) | 38 (7,273) → 13.5 (10,256) | 5.53 (7,287) → 3.02 (2,995) |
| keys on | 256 | 15.9 (27,864) → 11.5 (11,482) | 237 (106,372) → 276 (99,972) | 102 (33,368) → 205 (4,627) | 140 (7,273) → 68.7 (57,266) | 15.6 (26,567) → 11 (11,486) |
| keys off | 64 | 1.44 (510) → 1.36 (23) | 58.1 (16,832) → 11.2 (757) | 29.2 (16,434) → 13.9 (638) | 4.79 (1,858) → 0.744 (55) | 1.49 (509) → 1.36 (24) |
| keys off | 256 | 2.65 (510) → 5.66 (81) | 73.1 (16,832) → 45.6 (3,050) | 86.5 (16,684) → 53.9 (2,538) | 6.01 (1,858) → 3.61 (265) | 2.43 (509) → 5.6 (85) |
| MIN on | 64 | 4.88 (7,374) → 2.46 (2,994) | 162 (106,369) → 105 (148,310) | 62.8 (33,368) → 58 (2,727) | 37.9 (7,273) → 13.5 (10,256) | 5.18 (7,287) → 2.52 (2,995) |
| MIN on | 256 | 15.4 (27,848) → 9.4 (11,482) | 211 (106,370) → 270 (99,963) | 85.5 (33,368) → 71.3 (18,775) | 139 (7,273) → 68.4 (57,266) | 15.6 (26,551) → 9.38 (11,486) |

Plan shapes (custom-serial): the outer side is always `Index Only Scan using idx_outbox_record_incomplete_partition`
with `Index Cond: partition_no = ANY(...) AND status = 'NEW' AND next_retry_at <= now` (Heap Fetches ≈ 0), so
"rows removed by filter" on the outer side is gone (keys off B: 16.8k → 757 buffers at 64p). The rest:
- keys on, B 64p: nested-loop anti join with `incomplete_key` probes (49k probes, 148k buffers) + HashAggregate +
  Sort: 266 → 108 ms. B 256p: Sort + **merge anti join** whose outer side is an external-merge sort of 199k rows
  (13 MB spill) and whose inner side scans `incomplete_key` with mark/restore (97k buffers): 428 → 276 ms (−35%).
- keys on, C: 132 → 60 (64p) but 208 → 205 ms (256p, same merge-join shape). D: 38 → 13 / 139 → 68 ms (NL, probes
  still walk the join filter per key: 57k buffers at 256p). A/E: 5.3 → 3.0 / 20 → 11 ms.
- keys off: B 56 → 11 (64p) / 74 → 46 ms (256p; HashAggregate + Sort of 24.8k keys dominate); D 4.7 → 0.7 / 5.9 → 3.5;
  **A/E 256p slower: 2.8 → 5.5 ms** (Sort + GroupAggregate + Sort over 6k index entries instead of HashAggregate over
  the same rows from `idx_status`; tiny absolute values, but a plan regression).
- MIN on behaves like keys on (B 162 → 105 / 211 → 270 ms custom with workers: **256p slower** than control, the
  parallel hash plan of the control beats the serial merge join).

Generic (`generic-serial`): same shapes as custom; the 256-parameter disaster of the control is gone
(B keys on 915 → 276 ms, keys off 621 → 46 ms, C 787 → 205 ms).

H03b (non-partial covering `partition_status_retry ... INCLUDE (record_key, created_at, id)` replacing the existing
one, + `incomplete_key`, **original SQL**; `results/H03b-{B,D}/`), custom-serial ctl → H03b:

| Query | parts | B | D |
|---|---|---|---|
| keys on | 64 | 266 → 106 ms (53k buf) | 38 → 27 |
| keys on | 256 | 428 → 226 (56k) | 139 → 130 |
| keys off | 64 | 56 → 11.5 (1.0k) | 4.7 → 0.8 |
| keys off | 256 | 74 → 46 (4.1k) | 5.9 → 3.9 |
| MIN on (custom) | 64 / 256 | 162 → 104 / 211 → 215 | 38 → 28 / 139 → 129 |

H03b gives the same outer-side gain without the `completed_at IS NULL` query change (portable, works for every
status); D keeps the hash anti join (quadratic join filter).

## Equivalence check: PASS/FAIL + method
**PASS** (relies on the NEW ⇒ `completed_at IS NULL` invariant, which every seed satisfies). Scenarios A–E ×
parts 64/256 × 3 rewritten files (keys on, keys off, MIN on): exact PASS (30/30). Edge seed: exact PASS except
PASS-TIES in 4 checks with key_ties=1 (256p custom keys on and off, generic keys off 64p and 256p), plan-dependent tie order only.
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
Full baseline suite with both indexes applied post-seed (`results/H03-reg-{A,B}/`) vs `results/base-{A,B}/`:
- Plan changes: only the original-SQL `findRecordKeysInPartitions_filter` / `findOldestDueNextRetryAtByPartitions_filter`
  (inner side → `incomplete_key`, faster, same as H02). `incomplete_partition` is never chosen by the unmodified
  queries (`findRecordKeysInPartitions_nofilter`, `findOldestCreatedAtByPartitions`, `countRecordsByPartitions`,
  `findPendingRecords` lack `completed_at IS NULL`), so no flips there.
- Slower >10% with unchanged plan (noise): A `findOldestCreatedAtByPartitions` 256p generic 11.1 → 13.2 ms;
  B `deleteByIds` 2.96 → 3.29 ms.

Write cost (`results/G1-wc-{A,B}/{base,H03}.txt`, both indexes built incrementally before seeding):

| | insert ms/1k | insert WAL B/op | update ms/1k | update WAL B/op |
|---|---|---|---|---|
| A control → H03 | 25.9 → 27.7 (+7%) | 2449 → 2845 (+16%) | 36.1 → 36.7 (+2%) | 3307 → 3306 (0%) |
| B control → H03 | 26.1 → 29.4 (+13%) | 2436 → 2876 (+18%) | 35.1 → 36.2 (+3%) | 3328 → 3337 (0%) |

Index size: `incomplete_partition` A 107 pages (0.8 MB), B 4,431 pages (35 MB incremental; 20 MB post-seed build);
plus `incomplete_key` (H02). After the 50k write cycle without vacuum: A 1,136 pages, B 5,549 pages.
Total index pages B: 39,706 → 46,496 (+17%).

### Experimenter note
H03 fixes the outer side (owned partitions become an index condition, index-only) and makes generic plans sane, but
the query shape (aggregate + sort of every qualifying key, limit invisible to the planner) caps the gain: B serial
−59% at 64p but only −35% at 256p (merge join + spilling sort), the MIN query at 256p with workers gets slower, and
A/E filter-off at 256p doubles (small absolute). Cost: +16–18% insert WAL, the largest of the three, over the 15%
budget on its own. H03b shows most of the outer-side gain is available portably without the partial predicate.
Dominated by H04 on every target number; mainly useful as evidence that the outer-side index alone is not enough.

## Decision + reasoning
_pending_


## Orchestrator decision
rejected. dominated by H04; over write budget alone; A 256p off regresses (ledger: `../STATE.md`; final report: `../99-final-report.md`)
