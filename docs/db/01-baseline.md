# 01 Baseline: outbox_record queries on Postgres 18 (current schema)

Index set: the repository DDL (`namastack-outbox-jdbc/.../schema/postgres/outbox-tables.sql`):
`pkey(id)`, `record_key_created (record_key, created_at)`, `partition_status_retry (partition_no, status, next_retry_at)`,
`status_retry (status, next_retry_at)`, `status (status)`, `record_key_completed_created (record_key, completed_at, created_at)`.
Index names below drop the `idx_outbox_record_` prefix.

Raw output: `results/base-<scenario>/` (`summary.tsv`, one trimmed plan per query/variant/mode, `sizes.txt`,
`index-usage.txt`, `write-cost.txt`). Reproduce with `harness/run-suite.sh base-<S> <S>`.

## Method

- `postgres:18-alpine`, data dir on tmpfs, `shared_buffers=1GB` (everything is a buffer hit: `read=0` everywhere),
  `work_mem=4MB` (default), `jit=off`, 12-core host. Seeds are deterministic; heap order = `created_at` order.
- `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, SUMMARY ON)`, 1 warm-up + 3 runs, median execution time;
  buffers = shared hits of the root node of the median run. A separate single run with COSTS ON exists for hot-path
  and metrics queries (`*-costs.txt`).
- Fixed "now" = `bench_now` (`2026-01-01 00:00:00+00`); all seeded timestamps are 29-30 days before it.
- Partition sets: 64 = partitions 0..63 (4 instances), 256 = all (single instance). Key sets for
  `findIncompleteRecordPages`: the first 10/100/1000 keys the no-filter key query returns over all partitions
  (D has only 95 due keys, so keys=100 and keys=1000 are both 95 keys).
- Modes: `custom` = literal SQL; `serial` = same with `max_parallel_workers=0` (how pgjdbc executes a
  `withMaxRows` query, see below); `generic` = `PREPARE` + `plan_cache_mode=force_generic_plan`;
  `generic-serial` = both.
- Writes run inside `BEGIN ... ROLLBACK`. Cleanup cutoffs: 10th percentile of `completed_at` (COMPLETED),
  median `next_retry_at` (FAILED). `deleteByIds` gets the 1000 oldest COMPLETED ids before the cutoff.

| Scenario | rows | NEW | FAILED | keys with NEW | heap | all indexes |
|---|---|---|---|---|---|---|
| A steady state | 1,000,000 | 6,000 | 200 | 2,000 | 710 MB | 316 MB |
| B backlog | 1,000,000 | 200,000 | 200 | 25,000 | 698 MB | 311 MB |
| C delete-completed | 200,200 | 200,000 | 200 | 50,000 | 130 MB | 50 MB |
| D hot keys (1k keys x 1000) | 1,000,000 | 20,000 | 10 | 100 (95 due) | 709 MB | 336 MB |
| E blocked keys | 1,000,000 | 6,000 | 400 | 2,000 (400 blocked) | 710 MB | 316 MB |

All scenarios were seeded at full scale (1M rows; C 200k by definition). Seeding takes ~70 s per 1M rows.

Index sizes (A; B/D/E within a few MB, C ~1/7): pkey 73 MB, partition_status_retry 68 MB,
record_key_completed_created 68 MB, record_key_created 56 MB, status_retry 45 MB, status 6.4 MB
(B-tree deduplication makes the single-column `status` index tiny).

## Callers

| Method (SQL) | Caller | Class |
|---|---|---|
| `findRecordKeysInPartitions` (filter on/off) | `OutboxProcessingScheduler.loadRecordKeys`, every poll; filter = `stopOnFirstFailure` (default **true**); `withMaxRows(batchSize)`, default 10 | hot |
| `findIncompleteRecordPages` | `OutboxProcessingScheduler.processBatch`, every poll with keys; chunks of <= 1000 keys (default 10) | hot |
| `updateProcessingState` | `OutboxRecordProcessor.completeRecord`, `RetryOutboxRecordProcessor`, `PermanentFailureOutboxRecordProcessor`; once per processed record | hot |
| `create` (insert) | `OutboxService.schedule`, once per record x handler, in the caller's transaction | hot (producer) |
| `deleteById` | `OutboxRecordProcessor.completeRecord` when `delete-completed-records=true` | hot in C only |
| `countByStatus` | `OutboxMetricsCollector.collectRecordCounts` (all 3 statuses per refresh, default 15 s); `OutboxRecordMetricsMeterBinder` gauges (NEW/FAILED/COMPLETED per scrape). `OutboxInstanceRegistry.countByStatus` is the *instance* repository, not `outbox_record` | periodic |
| `countRecordsByPartitions` | `OutboxMetricsCollector.collectPartitionMetrics` | periodic |
| `countRecordsByPartition` | `OutboxPartitionMetricsProvider` (metrics module, once per owned partition); also the SPI default of `countRecordsByPartitions` | periodic |
| `findOldestCreatedAtByPartitions` | `OutboxMetricsCollector.collectPartitionMetrics` | periodic |
| `findOldestDueNextRetryAtByPartitions` (filter on/off) | `OutboxMetricsCollector.collectPartitionMetrics`, filter = `stopOnFirstFailure` | periodic |
| `findCompletedRecords(cutoff, limit)`, `findFailedRecords(cutoff, limit)`, `deleteCompletedRecords`, `deleteFailedRecords` (select ids + `deleteByIds`) | no caller in library main code; retention SPI for applications (ADR 0003), `withMaxRows(limit)` | cleanup |
| `deleteByStatus`, `deleteByRecordKeyAndStatus` | `OutboxActuatorEndpoint` | admin |
| `findPendingRecords`, `findCompletedRecords()`, `findFailedRecords()`, `findIncompleteRecordsByRecordKey`, `save`, `update` | no caller in library main code (SPI, tests) | admin |

Queries using `withMaxRows`: `findRecordKeysInPartitions` (both variants), `findCompletedRecords(cutoff, limit)`,
`findFailedRecords(cutoff, limit)` and the id selects of `deleteCompletedRecords` / `deleteFailedRecords`.

## `withMaxRows` and the planner (section 5.4)

Conclusion: **the limit is invisible to the planner, and it disables parallel execution.**

- Source (pgjdbc 42.7.14, `QueryExecutorImpl.sendOneQuery` / `calculateRowsToFetch`): `Statement.setMaxRows(n)`
  is only sent as the row count of the protocol `Execute` message (unnamed portal unless a fetch size is set).
  The SQL text is unchanged, so the server plans without a `LIMIT` (no `Limit` node, `tuple_fraction` 0).
- Server side (`exec_execute_message`): a portal executed with `max_rows > 0` is not "run once", and the executor
  then never launches parallel workers. The plan keeps its `Gather` node but runs it in the leader only.
- Verified with `harness/jdbc/MaxRowsProbe.java` (pgjdbc + `auto_explain`, `results/maxrows-probe-{A,B}.txt`):
  the executed plans have no `Limit` node; with maxRows=10 they show `Workers Launched: 0`, with maxRows=0
  `Workers Launched: 2`. Blocking nodes (Sort, HashAggregate, hash join) still consume the whole input; only the
  output is cut to n rows.
- Measured effect (client-side, includes auto_explain overhead): B key query (filter, 64 partitions) **275-300 ms**
  through pgjdbc vs 178 ms in psql EXPLAIN ANALYZE with workers; 375 ms for 256 partitions vs 217 ms. The `serial`
  mode reproduces this (266 ms / 367 ms). The cleanup id select with maxRows=10 took 152-167 ms, *slower* than the
  same query without a limit (104-107 ms), because the parallel plan runs serially.
- `plan_cache_mode=auto`: after pgjdbc switches to a server-side prepared statement (5th execution), Postgres kept
  the custom plan in all 7 executions of each probe (A: nofilter/256; B: filter/64, filter/256, nofilter/256).
  The generic plans measured below are therefore a risk, not the current behaviour; they would be used if the
  generic cost estimate became cheaper than the average custom plan, or with `plan_cache_mode=force_generic_plan`.
- Portability of a real limit, if it is added: Postgres/MySQL/MariaDB/H2 `LIMIT n`; Postgres/Oracle 12c+/SQL Server
  2012+ (needs `ORDER BY`)/H2 `FETCH FIRST n ROWS ONLY` (SQL Server: `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY`);
  SQL Server `TOP (n)`. For the key query a SQL limit does not help by itself (GROUP BY + ORDER BY MIN must see all
  rows); for the cleanup queries it enables a top-N heapsort or, with a `(status, completed_at)` index, an early stop.

## Hot path summary

`real` = how the application executes it today (pgjdbc, custom plan, `withMaxRows` => no parallel workers);
for queries without `withMaxRows` it is the plain custom plan. ms (shared buffer hits).

| Query | Parts / keys | A | B | C | D | E |
|---|---|---|---|---|---|---|
| `findRecordKeysInPartitions` filter (default) | 64 | 5.0 (7,374) | **265 (106,374)** | 132 (33,368) | 38 (7,273) | 5.1 (7,287) |
| | 256 | 19.2 (27,864) | **367 (106,363)** | 208 (33,368) | 141 (7,273) | 17.8 (26,567) |
| `findRecordKeysInPartitions` no filter | 64 | 1.3 (510) | 56 (16,832) | 30 (16,434) | 4.5 (1,858) | 1.4 (509) |
| | 256 | 2.4 (510) | 76 (16,832) | 77 (16,684) | 6.4 (1,858) | 2.5 (509) |
| `findIncompleteRecordPages` | 10 | 0.31 (42) | 0.18 (68) | 0.12 (38) | 3.9 (464) | 0.31 (42) |
| | 100 | 0.84 (335) | 6.6 (531) | 0.68 (345) | 40 (1,858) spill | 0.82 (334) |
| | 1000 | 4.0 (510) | 22 (3,864) | 12 (1,803) | 40 (1,858) spill | 4.1 (509) |
| `updateProcessingState` | 1 row | 0.09 (29) | 0.07 (29) | 0.08 (28) | 0.09 (30) | 0.06 (29) |
| `insert` | 1 row | 0.04 (14) | 0.04 (14) | 0.03 (15) | 0.03 (14) | 0.05 (25) |
| `deleteById` (C mode) | 1 row | 0.04 (5) | 0.04 (5) | 0.03 (5) | 0.05 (5) | 0.05 (5) |

Plan shapes of the key query with the filter (custom): A/E 64 = `idx_status` scan with partition as filter
+ NL anti join probing `record_key_completed_created (record_key, completed_at IS NULL)`; A/E 256 = parallel bitmap
scan (A on `partition_status_retry`, E on `status`) + same NL anti; **B = parallel index scan on `status` + parallel hash anti join whose inner
side is a parallel seq scan of the whole table** (`completed_at IS NULL` has no usable index), hash spills (4 batches);
C = parallel seq scan on both sides; D = hash anti join with a bitmap scan of `record_key_completed_created` on
`completed_at IS NULL`.


## Index usage after the workload mix (section 5.6)

`harness/workload-mix.sh`: 100 polls (filter on, 64 partitions, batch 10: key query, page load, one
`updateProcessingState` per due record), 10 metrics scrapes (`countByStatus` x 3, `countRecordsByPartitions`,
`findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions` filter on), 1 cleanup
(`deleteCompletedRecords` + `deleteFailedRecords`, limit 1000). `idx_scan` / `idx_tup_read`.
In Postgres 18, `idx_scan` counts every index search, so one IN-list query on 64 partitions adds 64.

| Index | A | B | C | D | E |
|---|---|---|---|---|---|
| pkey | 2,425 / 2.5k | 9,039 / 9.1k | 4,098 / 4.1k | 4,137 / 4.2k | 2,393 / 2.5k |
| status | 198 / 11.0M | 151 / 34.0M | 30 / 2.0M | 152 / 12.5M | 193 / 11.0M |
| record_key_completed_created | 47,048 / 79k | 1,440 / 20k | 1,169 / 4.4k | 110,550 / 2.2M | 54,931 / 82k |
| partition_status_retry | 640 / 5.6k | 640 / 458k | 20 / 962k | 640 / 9.2k | 640 / 6.3k |
| status_retry | 0 | 0 | 1 / 99 | 1 / 4 | 0 |
| record_key_created | **0** | **0** | **0** | 16 / 16k | **0** |
| table seq scans / tuples read | 3 / 1.0M | **223 / 111M** | **660 / 44M** | 3 / 1.0M | 3 / 1.0M |
| records processed (updates) | 1,386 | 8,000 | 4,000 | 3,200 | 1,257 |
| mix elapsed | 1.4 s | 21.0 s | 8.3 s | 3.0 s | 1.4 s |

- `record_key_created` is unused except 16 scans in D (planner picked it over `record_key_completed_created`
  for a few probes): the two `record_key` indexes are interchangeable for every query; drop candidate.
- `status_retry` is used only by `findOldestDueNextRetryAtByPartitions` without the filter (not the default),
  as an ordered `Limit` scan; drop/modify candidate.
- `partition_status_retry` is used only by `countRecordsByPartitions` (64 searches x 10 scrapes); the key query
  never uses it with 64 partitions.
- `status` carries most reads: `countByStatus(COMPLETED)` index-only scans (1M entries per scrape) and the
  key/oldest queries (all NEW rows table-wide, partitions applied as a filter).
- The seq scans in B and C come from the key query and `findOldestDueNextRetryAtByPartitions` (anti-join inner side;
  each parallel worker counts) and the cleanup id select.

## Write cost (section 5.7)

`harness/write-cost.sh`: pgbench `-M prepared`, 4 clients, 50k INSERT (`create`) then 50k `updateProcessingState`
(NEW -> COMPLETED) of exactly those rows, `synchronous_commit=off`. Run after the workload mix.

| Scenario | insert tps | insert ms / 1k ops | insert WAL B/op | update tps | update ms / 1k ops | update WAL B/op | index pages before -> after |
|---|---|---|---|---|---|---|---|
| A | 38,422 | 26.0 | 2,445 | 28,586 | 35.0 | 3,324 | 40,390 -> 43,374 |
| B | 38,417 | 26.0 | 2,437 | 29,330 | 34.1 | 3,342 | 39,786 -> 42,800 |
| C | 38,811 | 25.8 | 1,599 | 29,195 | 34.3 | 2,474 | - |
| D | 38,576 | 25.9 | 2,453 | 28,080 | 35.6 | 3,334 | - |
| E | 37,277 | 26.8 | 2,441 | 28,974 | 34.5 | 3,321 | - |

Run-to-run noise of tps is about +-3% (A vs E have the same schema and size); WAL bytes/op is stable to <1% and is
the better signal for comparing index sets. `updateProcessingState` is never HOT (it changes `status`,
`completed_at` and `next_retry_at`, which are indexed), so every index gets a new entry per update.

## Findings for Phase 1

1. **Key query with the filter (default) does not scale with owned work.** In B it builds a hash over all
   incomplete rows of the whole table from a parallel seq scan (106k buffers = entire heap), 265 ms per poll as
   executed by pgjdbc (serial), 367 ms for 256 partitions. In C 132-208 ms. Partitions are never an index condition
   with 64 partitions (idx_status + `partition_no = ANY` filter removes 3/4 of NEW rows).
2. **Anti-join work is quadratic in incomplete records per key**: D removes 358k (64 parts) / 1.9M (256) rows in the
   join filter for 20k NEW rows, because each NEW row probes all `completed_at IS NULL` rows of its key and filters
   by `created_at`/`id` afterwards (the index has `completed_at` before `created_at`, but `IS NULL` + range on
   `created_at` cannot be combined with the OR tie-breaker).
3. Without the filter the key query reads all NEW rows table-wide via `status` in A/B/D/E (B: 56-76 ms; C uses a
   bitmap on `partition_status_retry` for 64 partitions and a seq scan for 256), then HashAggregate
   + Sort; owned-partition selectivity is not used.
4. `findOldestDueNextRetryAtByPartitions` filter on: same shape and cost as the key query (B 165-214 ms, runs with
   workers because it has no `withMaxRows`). Without the filter 0.04 ms (`status_retry` + `Limit`).
5. `findOldestCreatedAtByPartitions`: scans all NEW rows via `status` (B 50 ms, C 20-26 ms; C/256 parallel seq scan).
   `countRecordsByPartitions` B 4.2 / 16.8 ms (index-only on `partition_status_retry`).
6. `countByStatus(COMPLETED)` ~20-24 ms per call (parallel index-only scan of `status`, grows with history); both
   metrics modules call it per scrape.
7. `findIncompleteRecordPages` is fine except D (95 keys x 200 NEW = 19k rows: 40 ms, external sort spill). Plans
   alternate between `status` index scans and BitmapAnd of `status` and a `record_key` index.
8. **Cleanup selects** (`findCompletedRecords(cutoff, limit)`, `deleteCompletedRecords` id select): parallel seq scan +
   external-merge sort of ~100k rows, 82-142 ms with workers, **157-273 ms as executed** (serial, `withMaxRows`),
   regardless of `limit`. FAILED cleanup is cheap (few rows, `status` index).
9. Admin: `findCompletedRecords()` 710-890 ms (sort of ~1M rows spills ~650 MB), `deleteByStatus(COMPLETED)`
   620-750 ms seq scan, `findPendingRecords` 212 ms in B. Acceptable for admin use.
10. Generic plans are much worse for partition queries (status and partitions unknown: planner assumes a large
    fraction of the table): B filter/256 872 ms, no-filter/256 624 ms generic-serial; A no-filter 2.4 -> 19.5 ms.
    Postgres currently keeps custom plans (probe), so this matters only if the generic estimate ever wins.

## Caveats

- Everything is cached (tmpfs, 1 GB shared_buffers): no I/O cost. On real disks with a cold cache the full-table scans
  (findings 1, 8, 9) get worse, index probes much less so.
- E differs from A only in 200 more FAILED heads (400 of 2000 keys with NEW); its numbers are within noise of A.
- D has 95 due keys, so `findIncompleteRecordPages` keys=100 and 1000 use the same 95 keys.
- C has no COMPLETED rows: `findCompletedRecords*`, `deleteCompletedRecords` and `deleteByIds` are trivially empty.
- The workload mix runs dynamic SQL in PL/pgSQL (custom plans, parallel workers allowed); index counters are not
  affected by the serial execution of the real application.
- Write cost uses `synchronous_commit=off` and 4 clients, so it measures index maintenance and WAL volume, not fsync.
- psql EXPLAIN uses a parallel plan where pgjdbc would run serially: use the `serial` rows for queries with
  `withMaxRows` (key query, cleanup selects).

## Full matrix (custom plans)

Cells: median ms (shared hits), `spill` = sort/hash to disk, `wN` = parallel workers launched, `rm` = rows removed by
filters. Generic/serial/costs variants: `results/base-<S>/summary.tsv`.

| query | variant | A | B | C | D | E |
|---|---|---|---|---|---|---|
| countByStatus | status=NEW | 0.33 (8) | 9.85 (161) | 9.71 (159) | 1.00 (19) | 0.33 (8) |
| countByStatus | status=FAILED | 0.04 (4) | 0.04 (4) | 0.04 (3) | 0.04 (4) | 0.05 (4) |
| countByStatus | status=COMPLETED | 23.85 (794) w2 | 19.59 (642) w2 | 0.03 (2) | 22.78 (783) w2 | 23.40 (794) w2 |
| countRecordsByPartition |  | 0.03 (4) | 0.10 (8) | 0.09 (8) | 0.04 (3) | 0.04 (4) |
| countRecordsByPartitions | parts=64 | 0.32 (232) | 4.17 (648) | 4.02 (272) | 0.46 (229) | 0.27 (232) |
| countRecordsByPartitions | parts=256 | 1.15 (510) | 16.81 (2,676) | 16.28 (1,109) | 2.23 (933) | 1.18 (509) |
| deleteById |  | 0.04 (5) | 0.04 (5) | 0.03 (5) | 0.05 (5) | 0.05 (5) |
| deleteByIds |  | 3.15 (3,926) | 2.96 (3,926) | 0.03 (3) | 3.07 (3,902) | 3.50 (3,926) |
| deleteByRecordKeyAndStatus |  | 0.27 (16) | 0.07 (31) rm 12 | 0.04 (12) rm 1 | 1.13 (427) | 0.26 (16) |
| deleteByStatus | status=FAILED | 0.12 (220) | 0.14 (220) | 0.11 (219) | 0.05 (15) | 0.23 (437) |
| deleteByStatus | status=COMPLETED | 737.42 (1,084,663) rm 6k | 620.36 (889,193) rm 200k | 0.03 (2) | 751.51 (1,070,738) rm 20k | 740.02 (1,084,461) rm 6k |
| deleteCompletedRecords_selectIds |  | 87.36 (90,939) w2,rm 300k | 81.68 (89,469) w2,rm 307k | 0.03 (2) | 82.76 (90,824) w2,rm 301k | 84.77 (90,937) w2,rm 300k |
| deleteFailedRecords_selectIds |  | 0.08 (20) rm 101 | 0.09 (20) rm 101 | 0.04 (12) | 0.03 (4) | 0.11 (37) rm 201 |
| findCompletedRecordsBefore |  | 140.76 (90,939) spill,w2,rm 300k | 125.60 (89,469) spill,w2,rm 307k | 0.04 (2) | 136.76 (90,824) spill,w2,rm 301k | 142.37 (90,937) spill,w2,rm 300k |
| findCompletedRecords |  | 887.55 (90,955) spill,w2,rm 2k | 710.04 (89,485) spill,w2,rm 67k | 0.04 (2) | 848.92 (90,840) spill,w2,rm 7k | 874.76 (90,953) spill,w2,rm 2k |
| findFailedRecordsBefore |  | 0.11 (20) rm 101 | 0.11 (20) rm 101 | 0.04 (12) | 0.03 (4) | 0.18 (37) rm 201 |
| findFailedRecords |  | 0.16 (20) | 0.15 (20) | 0.15 (19) | 0.05 (5) | 0.27 (37) |
| findIncompleteRecordPages | keys=10 | 0.31 (42) | 0.18 (68) rm 120 | 0.12 (38) rm 10 | 3.87 (464) | 0.31 (42) |
| findIncompleteRecordPages | keys=100 | 0.84 (335) | 6.63 (531) | 0.68 (345) rm 100 | 39.75 (1,858) spill,rm 1k | 0.82 (334) |
| findIncompleteRecordPages | keys=1000 | 4.04 (510) rm 3k | 21.95 (3,864) w2 | 12.13 (1,803) w2,rm 33 | 39.63 (1,858) spill,rm 1k | 4.08 (509) rm 3k |
| findIncompleteRecordsByRecordKey |  | 0.28 (13) | 0.05 (23) rm 12 | 0.04 (8) rm 1 | 1.06 (227) | 0.24 (13) |
| findOldestCreatedAtByPartitions | parts=64 | 1.00 (510) rm 4k | 49.65 (16,832) rm 151k | 19.91 (16,460) | 4.55 (1,858) rm 16k | 1.13 (509) rm 4k |
| findOldestCreatedAtByPartitions | parts=256 | 1.23 (510) | 50.13 (16,832) | 25.63 (16,684) w2,rm 67 | 4.78 (1,858) | 1.14 (509) |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | 4.72 (7,374) rm 5k | 165.32 (106,371) spill,w1,rm 562k | 65.82 (33,368) w2,rm 92k | 38.84 (7,273) rm 358k | 4.71 (7,287) rm 5k |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | 15.68 (27,848) w2,rm 1 | 214.41 (106,372) spill,w1,rm 734k | 83.75 (33,368) w2,rm 167k | 139.34 (7,273) rm 1910k | 14.73 (26,551) w2,rm 101 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | 0.04 (4) rm 1 | 0.04 (4) rm 1 | 0.03 (4) rm 1 | 0.03 (4) | 0.04 (4) rm 1 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | 0.04 (4) | 0.05 (4) | 0.04 (4) | 0.04 (4) | 0.04 (4) |
| findPendingRecords |  | 3.13 (510) | 212.46 (16,942) spill,w1 | 134.26 (16,776) spill,w2,rm 67 | 22.77 (1,858) spill | 3.10 (509) |
| findRecordKeysInPartitions_filter | parts=64 | 5.05 (7,374) rm 5k | 177.58 (106,374) spill,w1,rm 560k | 65.41 (33,368) w2,rm 92k | 38.05 (7,273) rm 358k | 5.05 (7,287) rm 5k |
| findRecordKeysInPartitions_filter | parts=256 | 15.65 (27,864) w2,rm 1 | 217.20 (106,363) spill,w1,rm 736k | 102.69 (33,368) w2,rm 167k | 138.59 (7,273) rm 1910k | 14.88 (26,567) w2,rm 101 |
| findRecordKeysInPartitions_nofilter | parts=64 | 1.38 (510) rm 5k | 53.94 (16,832) rm 151k | 29.88 (16,434) | 4.96 (1,858) rm 17k | 1.44 (509) rm 5k |
| findRecordKeysInPartitions_nofilter | parts=256 | 2.37 (510) rm 300 | 70.57 (16,832) rm 800 | 75.69 (16,684) rm 600 | 6.54 (1,858) rm 1k | 2.61 (509) rm 300 |
| insert |  | 0.04 (14) | 0.04 (14) | 0.03 (15) | 0.03 (14) | 0.05 (25) |
| updateProcessingState |  | 0.09 (29) | 0.07 (29) | 0.08 (28) | 0.09 (30) | 0.06 (29) |
| update |  | 0.07 (29) | 0.09 (29) | 0.09 (28) | 0.08 (30) | 0.07 (29) |

### Plan shapes and indexes (custom plans)

Abbreviations: IS index scan, IOS index-only, BIS/BHS bitmap index/heap scan, P. parallel, GatherM gather merge, NL-Anti nested-loop anti join.

| query | variant | A | B | C | D | E |
|---|---|---|---|---|---|---|
| countByStatus | status=NEW | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] |
| countByStatus | status=FAILED | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] | Agg(IOS) [status] |
| countByStatus | status=COMPLETED | FinAgg(Gather(PartAgg(P.IOS))) [status] | FinAgg(Gather(PartAgg(P.IOS))) [status] | Agg(IOS) [status] | FinAgg(Gather(PartAgg(P.IOS))) [status] | FinAgg(Gather(PartAgg(P.IOS))) [status] |
| countRecordsByPartition |  | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] |
| countRecordsByPartitions | parts=64 | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] |
| countRecordsByPartitions | parts=256 | Agg(IS) [status] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IOS) [partition_status_retry] | Agg(IS) [status] |
| deleteById |  | Delete(IS) [pkey] | Delete(IS) [pkey] | Delete(IS) [pkey] | Delete(IS) [pkey] | Delete(IS) [pkey] |
| deleteByIds |  | Delete(BHS(BIS)) [pkey] | Delete(BHS(BIS)) [pkey] | Delete(IS) [pkey] | Delete(BHS(BIS)) [pkey] | Delete(BHS(BIS)) [pkey] |
| deleteByRecordKeyAndStatus |  | Delete(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] | Delete(IS) [record_key_completed_created] | Delete(IS) [record_key_completed_created] | Delete(BHS(BitmapAnd(BIS,BIS))) [record_key_created+status] | Delete(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] |
| deleteByStatus | status=FAILED | Delete(IS) [status] | Delete(IS) [status] | Delete(IS) [status] | Delete(IS) [status] | Delete(IS) [status] |
| deleteByStatus | status=COMPLETED | Delete(Seq) [-] | Delete(Seq) [-] | Delete(IS) [status] | Delete(Seq) [-] | Delete(Seq) [-] |
| deleteCompletedRecords_selectIds |  | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] | Sort(IS) [status] | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] |
| deleteFailedRecords_selectIds |  | Sort(IS) [status] | Sort(IS) [status] | IS [status_retry] | IS [status_retry] | Sort(IS) [status] |
| findCompletedRecordsBefore |  | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] | Sort(IS) [status] | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] |
| findCompletedRecords |  | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] | Sort(IS) [status] | GatherM(Sort(P.Seq)) [-] | GatherM(Sort(P.Seq)) [-] |
| findFailedRecordsBefore |  | Sort(IS) [status] | Sort(IS) [status] | IS [status_retry] | IS [status_retry] | Sort(IS) [status] |
| findFailedRecords |  | Sort(IS) [status] | Sort(IS) [status] | Sort(IS) [status] | Sort(IS) [status] | Sort(IS) [status] |
| findIncompleteRecordPages | keys=10 | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] | Sort(BHS(BIS)) [record_key_completed_created] | Sort(BHS(BIS)) [record_key_completed_created] | Sort(BHS(BitmapAnd(BIS,BIS))) [status+record_key_created] | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] |
| findIncompleteRecordPages | keys=100 | Sort(BHS(BitmapAnd(BIS,BIS))) [status+record_key_completed_created] | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] | Sort(BHS(BIS)) [record_key_completed_created] | Sort(IS) [status] | Sort(BHS(BitmapAnd(BIS,BIS))) [status+record_key_completed_created] |
| findIncompleteRecordPages | keys=1000 | Sort(IS) [status] | GatherM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [status+record_key_created] | GatherM(Sort(P.BHS(BIS))) [record_key_completed_created] | Sort(IS) [status] | Sort(IS) [status] |
| findIncompleteRecordsByRecordKey |  | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] | IncSort(IS) [record_key_created] | IncSort(IS) [record_key_created] | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_created+status] | Sort(BHS(BitmapAnd(BIS,BIS))) [record_key_completed_created+status] |
| findOldestCreatedAtByPartitions | parts=64 | Agg(IS) [status] | Agg(IS) [status] | Agg(BHS(BIS)) [partition_status_retry] | Agg(IS) [status] | Agg(IS) [status] |
| findOldestCreatedAtByPartitions | parts=256 | Agg(IS) [status] | Agg(IS) [status] | FinAgg(Gather(PartAgg(P.Seq))) [-] | Agg(IS) [status] | Agg(IS) [status] |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | Agg(NL-Anti(IS,IS)) [status+record_key_completed_created] | FinAgg(Gather(PartAgg(P.HashAnti(P.IS,P.Hash(P.Seq))))) [status] | FinAgg(Gather(PartAgg(P.HashAnti(P.Seq,P.Hash(P.Seq))))) [-] | Agg(HashAnti(IS,Hash(BHS(BIS)))) [status+record_key_completed_created] | Agg(NL-Anti(IS,IS)) [status+record_key_completed_created] |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | FinAgg(Gather(PartAgg(NL-Anti(P.BHS(BIS),IS)))) [partition_status_retry+record_key_completed_created] | FinAgg(Gather(PartAgg(P.HashAnti(P.IS,P.Hash(P.Seq))))) [status] | FinAgg(Gather(PartAgg(P.HashAnti(P.Seq,P.Hash(P.Seq))))) [-] | Agg(HashAnti(IS,Hash(BHS(BIS)))) [status+record_key_completed_created] | FinAgg(Gather(PartAgg(NL-Anti(P.BHS(BIS),IS)))) [status+record_key_completed_created] |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] | Result(Limit(IS)) [status_retry] |
| findPendingRecords |  | Sort(IS) [status] | GatherM(Sort(P.IS)) [status] | GatherM(Sort(P.Seq)) [-] | Sort(IS) [status] | Sort(IS) [status] |
| findRecordKeysInPartitions_filter | parts=64 | Sort(GroupAgg(Sort(NL-Anti(IS,IS)))) [status+record_key_completed_created] | Sort(HashAgg(Gather(P.HashAnti(P.IS,P.Hash(P.Seq))))) [status] | Sort(HashAgg(Gather(P.HashAnti(P.Seq,P.Hash(P.Seq))))) [-] | Sort(GroupAgg(Sort(HashAnti(IS,Hash(BHS(BIS)))))) [status+record_key_completed_created] | Sort(GroupAgg(Sort(NL-Anti(IS,IS)))) [status+record_key_completed_created] |
| findRecordKeysInPartitions_filter | parts=256 | Sort(GroupAgg(GatherM(Sort(NL-Anti(P.BHS(BIS),IS))))) [partition_status_retry+record_key_completed_created] | Sort(HashAgg(Gather(P.HashAnti(P.IS,P.Hash(P.Seq))))) [status] | Sort(HashAgg(Gather(P.HashAnti(P.Seq,P.Hash(P.Seq))))) [-] | Sort(GroupAgg(Sort(HashAnti(IS,Hash(BHS(BIS)))))) [status+record_key_completed_created] | Sort(GroupAgg(GatherM(Sort(NL-Anti(P.BHS(BIS),IS))))) [status+record_key_completed_created] |
| findRecordKeysInPartitions_nofilter | parts=64 | Sort(HashAgg(IS)) [status] | Sort(HashAgg(IS)) [status] | Sort(HashAgg(BHS(BIS))) [partition_status_retry] | Sort(HashAgg(IS)) [status] | Sort(HashAgg(IS)) [status] |
| findRecordKeysInPartitions_nofilter | parts=256 | Sort(HashAgg(IS)) [status] | Sort(HashAgg(IS)) [status] | Sort(HashAgg(Seq)) [-] | Sort(HashAgg(IS)) [status] | Sort(HashAgg(IS)) [status] |
| insert |  | Insert(Result) [-] | Insert(Result) [-] | Insert(Result) [-] | Insert(Result) [-] | Insert(Result) [-] |
| updateProcessingState |  | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] |
| update |  | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] | Update(IS) [pkey] |

### Custom vs serial vs generic (queries with a generic branch or withMaxRows)

| query | variant | mode | A | B | C | D | E |
|---|---|---|---|---|---|---|---|
| countByStatus | status=NEW | custom | 0.33 (8) | 9.85 (161) | 9.71 (159) | 1.00 (19) | 0.33 (8) |
| countByStatus | status=NEW | generic | 5.91 (17) w2 | 8.40 (187) w2 | 9.68 (165) | 5.75 (479) w2 | 6.35 (15) w2 |
| countByStatus | status=FAILED | custom | 0.04 (4) | 0.04 (4) | 0.04 (3) | 0.04 (4) | 0.05 (4) |
| countByStatus | status=FAILED | generic | 5.99 (21) w2 | 5.69 (21) w2 | 0.06 (20) | 6.44 (6) w2 | 6.00 (38) w2 |
| countByStatus | status=COMPLETED | custom | 23.85 (794) w2 | 19.59 (642) w2 | 0.03 (2) | 22.78 (783) w2 | 23.40 (794) w2 |
| countByStatus | status=COMPLETED | generic | 82.12 (91,829) w2 | 67.08 (73,907) w2 | 0.03 (2) | 81.60 (90,745) w2 | 81.21 (91,814) w2 |
| countRecordsByPartitions | parts=64 | custom | 0.32 (232) | 4.17 (648) | 4.02 (272) | 0.46 (229) | 0.27 (232) |
| countRecordsByPartitions | parts=64 | generic | 0.32 (253) | 4.16 (714) | 4.02 (293) | 0.73 (1,309) | 0.31 (250) |
| countRecordsByPartitions | parts=256 | custom | 1.15 (510) | 16.81 (2,676) | 16.28 (1,109) | 2.23 (933) | 1.18 (509) |
| countRecordsByPartitions | parts=256 | generic | 1.15 (992) | 16.93 (2,869) | 16.09 (1,163) | 3.06 (4,373) | 1.08 (985) |
| deleteById |  | custom | 0.04 (5) | 0.04 (5) | 0.03 (5) | 0.05 (5) | 0.05 (5) |
| deleteById |  | generic | 0.03 (5) | 0.03 (5) | 0.03 (5) | 0.04 (5) | 0.04 (5) |
| deleteCompletedRecords_selectIds |  | custom | 87.36 (90,939) w2,rm 300k | 81.68 (89,469) w2,rm 307k | 0.03 (2) | 82.76 (90,824) w2,rm 301k | 84.77 (90,937) w2,rm 300k |
| deleteCompletedRecords_selectIds |  | custom-serial | 170.19 (90,863) spill,w0,rm 901k | 158.96 (89,394) spill,w0,rm 920k | 0.04 (2) | 156.61 (90,749) spill,w0,rm 902k | 170.53 (90,863) spill,w0,rm 901k |
| deleteFailedRecords_selectIds |  | custom | 0.08 (20) rm 101 | 0.09 (20) rm 101 | 0.04 (12) | 0.03 (4) | 0.11 (37) rm 201 |
| deleteFailedRecords_selectIds |  | custom-serial | 0.08 (20) rm 101 | 0.08 (20) rm 101 | 0.04 (12) | 0.03 (4) | 0.12 (37) rm 201 |
| findCompletedRecordsBefore |  | custom | 140.76 (90,939) spill,w2,rm 300k | 125.60 (89,469) spill,w2,rm 307k | 0.04 (2) | 136.76 (90,824) spill,w2,rm 301k | 142.37 (90,937) spill,w2,rm 300k |
| findCompletedRecordsBefore |  | custom-serial | 273.21 (90,863) spill,w0,rm 901k | 211.21 (89,394) spill,w0,rm 920k | 0.03 (2) | 252.83 (90,749) spill,w0,rm 902k | 269.66 (90,863) spill,w0,rm 901k |
| findFailedRecordsBefore |  | custom | 0.11 (20) rm 101 | 0.11 (20) rm 101 | 0.04 (12) | 0.03 (4) | 0.18 (37) rm 201 |
| findFailedRecordsBefore |  | custom-serial | 0.12 (20) rm 101 | 0.11 (20) rm 101 | 0.04 (12) | 0.04 (4) | 0.20 (37) rm 201 |
| findIncompleteRecordPages | keys=10 | custom | 0.31 (42) | 0.18 (68) rm 120 | 0.12 (38) rm 10 | 3.87 (464) | 0.31 (42) |
| findIncompleteRecordPages | keys=10 | generic | 0.19 (66) rm 170 | 0.20 (69) rm 120 | 0.11 (40) rm 10 | 6.36 (536) w2 | 0.13 (67) rm 170 |
| findIncompleteRecordPages | keys=100 | custom | 0.84 (335) | 6.63 (531) | 0.68 (345) rm 100 | 39.75 (1,858) spill,rm 1k | 0.82 (334) |
| findIncompleteRecordPages | keys=100 | generic | 0.89 (335) | 6.59 (531) | 0.73 (347) rm 100 | 43.76 (1,904) spill,w2,rm 333 | 0.86 (334) |
| findIncompleteRecordPages | keys=1000 | custom | 4.04 (510) rm 3k | 21.95 (3,864) w2 | 12.13 (1,803) w2,rm 33 | 39.63 (1,858) spill,rm 1k | 4.08 (509) rm 3k |
| findIncompleteRecordPages | keys=1000 | generic | 11.38 (3,293) w2 | 22.06 (3,864) w2 | 13.45 (1,880) | 43.92 (1,903) spill,w2,rm 333 | 11.26 (3,292) w2 |
| findIncompleteRecordsByRecordKey |  | custom | 0.28 (13) | 0.05 (23) rm 12 | 0.04 (8) rm 1 | 1.06 (227) | 0.24 (13) |
| findIncompleteRecordsByRecordKey |  | generic | 0.05 (23) rm 17 | 0.05 (23) rm 12 | 0.04 (8) rm 1 | 0.79 (1,010) rm 800 | 0.05 (23) rm 17 |
| findOldestCreatedAtByPartitions | parts=64 | custom | 1.00 (510) rm 4k | 49.65 (16,832) rm 151k | 19.91 (16,460) | 4.55 (1,858) rm 16k | 1.13 (509) rm 4k |
| findOldestCreatedAtByPartitions | parts=64 | generic | 7.41 (512) w2,rm 1k | 78.87 (16,976) w2,rm 50k | 115.29 (16,977) w1,rm 75k | 12.53 (1,870) w2,rm 5k | 7.55 (511) w2,rm 1k |
| findOldestCreatedAtByPartitions | parts=256 | custom | 1.23 (510) | 50.13 (16,832) | 25.63 (16,684) w2,rm 67 | 4.78 (1,858) | 1.14 (509) |
| findOldestCreatedAtByPartitions | parts=256 | generic | 11.08 (513) w2 | 212.03 (16,978) w2 | 310.18 (16,979) w1 | 27.26 (1,871) w2 | 11.27 (513) w2 |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | custom | 4.72 (7,374) rm 5k | 165.32 (106,371) spill,w1,rm 562k | 65.82 (33,368) w2,rm 92k | 38.84 (7,273) rm 358k | 4.71 (7,287) rm 5k |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | generic | 10.45 (7,379) w2,rm 2k | 122.18 (106,010) w2,rm 340k | 52.65 (33,119) w2,rm 42k | 26.50 (7,326) w2,rm 119k | 10.52 (7,292) w2,rm 2k |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | custom | 15.68 (27,848) w2,rm 1 | 214.41 (106,372) spill,w1,rm 734k | 83.75 (33,368) w2,rm 167k | 139.34 (7,273) rm 1910k | 14.73 (26,551) w2,rm 101 |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | generic | 58.05 (9,754) w2,rm 4k | 329.45 (106,372) w2,rm 566k | 298.17 (34,089) w2,rm 166k | 76.58 (7,324) w2,rm 637k | 60.37 (9,769) w2,rm 4k |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | custom | 0.04 (4) rm 1 | 0.04 (4) rm 1 | 0.03 (4) rm 1 | 0.03 (4) | 0.04 (4) rm 1 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | generic | 0.06 (4) rm 1 | 0.05 (4) rm 1 | 0.04 (4) rm 1 | 0.04 (4) | 0.05 (4) rm 1 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | custom | 0.04 (4) | 0.05 (4) | 0.04 (4) | 0.04 (4) | 0.04 (4) |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | generic | 0.08 (4) | 0.09 (4) | 0.08 (4) | 0.08 (4) | 0.09 (4) |
| findRecordKeysInPartitions_filter | parts=64 | custom | 5.05 (7,374) rm 5k | 177.58 (106,374) spill,w1,rm 560k | 65.41 (33,368) w2,rm 92k | 38.05 (7,273) rm 358k | 5.05 (7,287) rm 5k |
| findRecordKeysInPartitions_filter | parts=64 | custom-serial | 5.05 (7,374) rm 5k | 265.46 (106,226) spill,w0,rm 1172k | 132.04 (33,370) w0,rm 275k | 38.09 (7,273) rm 358k | 5.10 (7,288) rm 5k |
| findRecordKeysInPartitions_filter | parts=64 | generic | 10.28 (7,379) w2,rm 2k | 127.29 (106,026) w2,rm 340k | 65.74 (33,135) w2,rm 42k | 26.15 (7,341) w2,rm 119k | 10.07 (7,292) w2,rm 2k |
| findRecordKeysInPartitions_filter | parts=64 | generic-serial | 38.88 (9,678) w0,rm 7k | 231.94 (106,009) w0,rm 1021k | 102.27 (33,119) w0,rm 125k | 54.23 (7,273) w0,rm 358k | 10.14 (7,288) w0,rm 5k |
| findRecordKeysInPartitions_filter | parts=256 | custom | 15.65 (27,864) w2,rm 1 | 217.20 (106,363) spill,w1,rm 736k | 102.69 (33,368) w2,rm 167k | 138.59 (7,273) rm 1910k | 14.88 (26,567) w2,rm 101 |
| findRecordKeysInPartitions_filter | parts=256 | custom-serial | 19.18 (27,807) w0,rm 1 | 366.94 (106,226) spill,w0,rm 1697k | 208.08 (33,370) w0,rm 500k | 140.81 (7,273) rm 1910k | 17.83 (26,509) w0,rm 301 |
| findRecordKeysInPartitions_filter | parts=256 | generic | 43.18 (9,722) w2,rm 4k | 330.87 (106,371) w2,rm 566k | 313.31 (34,089) w2,rm 166k | 75.75 (7,340) w2,rm 637k | 44.41 (9,739) w2,rm 4k |
| findRecordKeysInPartitions_filter | parts=256 | generic-serial | 49.53 (9,678) w0,rm 12k | 871.79 (106,225) w0,rm 1697k | 777.36 (34,090) w0,rm 499k | 194.83 (7,273) w0,rm 1910k | 49.90 (9,694) w0,rm 12k |
| findRecordKeysInPartitions_nofilter | parts=64 | custom | 1.38 (510) rm 5k | 53.94 (16,832) rm 151k | 29.88 (16,434) | 4.96 (1,858) rm 17k | 1.44 (509) rm 5k |
| findRecordKeysInPartitions_nofilter | parts=64 | custom-serial | 1.31 (510) rm 5k | 56.36 (16,832) rm 151k | 29.58 (16,434) | 4.54 (1,858) rm 17k | 1.38 (510) rm 5k |
| findRecordKeysInPartitions_nofilter | parts=64 | generic | 7.47 (512) w2,rm 2k | 27.22 (16,616) | 30.01 (16,434) | 12.66 (1,886) w2,rm 6k | 7.28 (511) w2,rm 2k |
| findRecordKeysInPartitions_nofilter | parts=64 | generic-serial | 6.79 (510) w0,rm 5k | 27.27 (16,616) | 31.81 (16,434) | 20.28 (1,858) w0,rm 17k | 6.64 (510) w0,rm 5k |
| findRecordKeysInPartitions_nofilter | parts=256 | custom | 2.37 (510) rm 300 | 70.57 (16,832) rm 800 | 75.69 (16,684) rm 600 | 6.54 (1,858) rm 1k | 2.61 (509) rm 300 |
| findRecordKeysInPartitions_nofilter | parts=256 | custom-serial | 2.36 (510) rm 300 | 75.62 (16,832) rm 800 | 76.82 (16,685) rm 600 | 6.41 (1,858) rm 1k | 2.46 (510) rm 300 |
| findRecordKeysInPartitions_nofilter | parts=256 | generic | 13.43 (514) w2,rm 100 | 232.19 (16,978) w2,rm 267 | 247.77 (17,444) w2 | 27.02 (1,887) w2,rm 333 | 13.45 (513) w2,rm 100 |
| findRecordKeysInPartitions_nofilter | parts=256 | generic-serial | 19.48 (510) w0,rm 300 | 623.79 (16,832) w0,rm 800 | 632.20 (17,405) w0 | 59.76 (1,858) w0,rm 1k | 19.22 (510) w0,rm 300 |
| insert |  | custom | 0.04 (14) | 0.04 (14) | 0.03 (15) | 0.03 (14) | 0.05 (25) |
| insert |  | generic | 0.05 (15) | 0.04 (14) | 0.04 (15) | 0.04 (14) | 0.06 (14) |
| updateProcessingState |  | custom | 0.09 (29) | 0.07 (29) | 0.08 (28) | 0.09 (30) | 0.06 (29) |
| updateProcessingState |  | generic | 0.10 (29) | 0.08 (29) | 0.08 (28) | 0.11 (30) | 0.08 (31) |

