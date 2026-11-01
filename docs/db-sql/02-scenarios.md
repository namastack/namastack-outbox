# Scenario matrix

Derived from `01-usage-map.md` (baseline) and `01-usage-map-v1-delta.md` (v1). All scenarios are deterministic
(seed `42` unless stated) and generated identically for every variant.

## Global conventions

| Parameter | Value |
|---|---|
| Reference time `T0` | `2026-01-01T12:00:00Z`. Every timestamp is generated relative to `T0`; every query uses `now = T0`. |
| Partitions | 256 (real `PartitionHasher`: MurmurHash3 x86-32, seed 0, of `record_key`). |
| Owned partitions | all 256 (one instance) unless stated. S8: 64 partitions = the set one of 4 instances would own (partitions `p % 4 == 0`). |
| `record_key` | `"order-" + zero-padded key number` padded to ~36 chars (UUID-like length). |
| `id` | UUIDv4 from the seeded RNG (random order, it is only a tie-breaker). |
| `record_type` | one of 5 class names (~45 chars). `handler_id` one of 3 strings (~80 chars). |
| `payload` | JSON, **500 bytes** (S2-10M: 200 bytes, disk budget). `context` JSON ~100 bytes or NULL (50 %). |
| `created_at` | per key strictly increasing; ~2 % of records share `created_at` with their predecessor of the same key (exercise the `id` tie-break). Microsecond precision where the dialect stores it. |
| COMPLETED rows | `completed_at = created_at + U(5 ms, 2 s)`, `next_retry_at = created_at` or last retry time, `failure_count` 0 (90 %) / 1–3. |
| NEW due | `next_retry_at <= T0`. NEW future retry: `next_retry_at = T0 + U(1 min, 1 h)`, `failure_count` 1–3. |
| FAILED | `completed_at NULL`, `failure_count` 4, `failure_reason` ~200 chars, `next_retry_at` = last scheduled attempt. |
| Repository parameters | `batchSize = 10` (default), strict mode `ignoreRecordKeysWithPreviousFailure = true` (default `stopOnFirstFailure`); non-strict measured as its own query id. Pages: the keys returned by the key query (≤ batchSize, S4 also 100). |
| Retention parameters | `deleteCompletedRecords(before = T0 - 7 d, limit = 1000)`, `deleteFailedRecords(before = T0 - 30 d, limit = 1000)`; same values for the `find*(before, limit)` listings. |
| Statistics | `ANALYZE` (or dialect equivalent) after load; S9 relies on autovacuum only. |

Status mix invariants follow `01-usage-map.md` §2.1 (`completed_at IS NULL` ⇔ NEW/FAILED).

## Scenarios

| Id | Scenario | Rows | Status mix | Keys / distribution | Retries | Partitions | Retention | Churn / concurrency |
|---|---|---|---|---|---|---|---|---|
| **S1** | Small table | 10 000 | 9 800 COMPLETED, 150 NEW due, 30 NEW future, 20 FAILED | 5 000 keys, 2 records/key avg (uniform 1–3) | 30 future | 256 | off | none |
| **S2** | Steady state, large completed tail (1M) | 1 000 000 | 98.6 % COMPLETED (986 000), 1 000 NEW due, 200 NEW future, 12 800 FAILED | 200 000 keys, uniform 1–9 records/key; NEW records are the newest of their key; 5 % of NEW-due keys have an older FAILED or future-retry NEW (blocked) | 200 future | 256 | off (COMPLETED accumulate, created over the last 30 d) | none |
| **S2L** | Same, 10M (PostgreSQL only, payload 200 B) | 10 000 000 | same ratios (NEW due 1 000, NEW future 200, FAILED 128 000) | 2 000 000 keys | 200 | 256 | off | none. Run only for baseline, v1 and final v2 (and an experiment if 1M is inconclusive). |
| **S3** | Retention on, small table | 5 000 live rows | 3 000 NEW due, 500 NEW future, 1 500 FAILED, **0 COMPLETED** (`delete-completed-records=true`) | 4 000 keys | 500 | 256 | delete-on-complete (`deleteById`) | write group: insert + `deleteById` cycle (table size stays constant) |
| **S4** | Backlog drain | 2 000 000 | 1 000 000 NEW due + 1 000 000 COMPLETED (older) | NEW: 1 record per key (1M keys); COMPLETED: other 500 000 keys × 2 | none | 256 | off | key query at batchSize 10 and 100; pages at 10 and 100 keys |
| **S5** | Hot keys / head-of-line blocking | 500 000 | 200 000 NEW (100 keys × 2 000), 300 000 COMPLETED (same 100 keys, older) | 100 hot keys; of those 20 have a FAILED head, 20 a NEW head with future retry, 60 a due head | 20 heads future | 256 | off | strict and non-strict |
| **S6** | Retry heavy | 1 000 000 | 800 000 COMPLETED, 195 000 NEW future (`T0 + 1 min..1 h`), 5 000 NEW due | 150 000 NEW keys, 1–2 NEW per key, plus completed history | 195 000 | 256 | off | none |
| **S7** | Many FAILED | 1 000 000 | 600 000 COMPLETED, 390 000 FAILED (`next_retry_at` spread over `T0 - 90 d .. T0`), 10 000 NEW due | 300 000 keys | none | 256 | listing + `deleteFailedRecords` (limit 1000), `deleteByStatus(FAILED)` (rolled back) | none |
| **S8** | Partial ownership | S2 data and S4 data | as S2 / S4 | as S2 / S4 | as S2 / S4 | **64 of 256** (`p % 4 == 0`) | off | parameter set only, no new data |
| **S9** | Churn / bloat (PostgreSQL focus) | starts as S2 (1M) | after churn: same mix as S2 | as S2 | as S2 | 256 | delete-on-complete for 30 % of keys, `deleteCompletedRecords` batches for the rest | churn phase: 500 000 inserts, each processed (1 update; 10 % 1–3 retry updates; 2 % FAILED), retention deletes ~500 000 oldest COMPLETED. Autovacuum at defaults, **no manual VACUUM/ANALYZE** after churn. Record `n_dead_tup`, table/index sizes before/after. |
| **S10** | Concurrency | S2 data | as S2 | as S2 | as S2 | 1 poller × 256, 4 pollers × 64 (disjoint) | off | 60 s run, processing loop (find keys → pages → `updateProcessingState` complete) with a producer inserting **500 rec/s** (and a second run with an unthrottled producer, 4 threads). Report processed rec/s, key-query and pages p50/p95, insert p50/p95. |

## Query groups and ids

The harness assigns ids. A query not applicable to a variant (e.g. `countByStatuses` on main) is reported as `n/a`.

| Group | Query ids |
|---|---|
| **HOT** | `keys_strict` (findRecordKeysInPartitions, strict), `keys_nonstrict`, `pages` (findIncompleteRecordPages / findByKeysAndStatus) |
| **WRITE** | `insert` (create), `upd_complete`, `upd_retry`, `upd_fail` (updateProcessingState), `delete_by_id` |
| **MON** | `count_new`, `count_failed`, `count_completed` (countByStatus), `count_statuses` (v1 countByStatuses, NEW+FAILED and all three), `count_partitions` (countRecordsByPartitions, NEW), `count_partition_single` (deprecated metrics module, one partition), `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` |
| **RET** | `find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before`, `delete_by_ids` (1000 ids), `delete_by_status_failed` (rolled back), `delete_by_key_status` |
| **ADMIN** | `find_pending_all`, `find_failed_all` (unbounded; S1, S3 only), `find_incomplete_by_key` (single key) |

Monitoring frequency weights for interpretation (from the usage map): HOT key query 0.5/s/instance even when idle; MON 6 queries per 15 s per instance; RET caller-driven; ADMIN manual.

## Dialect × scenario matrix

| Runner / dialect | Scenarios |
|---|---|
| SQL-level, PostgreSQL | S1–S10 (S2L only for baseline / v1 / v2) |
| SQL-level, MySQL, MariaDB, SQL Server, Oracle | S1, S2, S4, S5, S7, S8 (S2+S4 params) |
| SQL-level, H2 (in-process) | S1, S2 |
| Repository-level, JDBC PostgreSQL | S1, S2, S4, S5 |
| Repository-level, JPA PostgreSQL | S1, S2, S4, S5 |

Repetitions: warmup 5, measured 20 (queries with median > 2 s: warmup 1, measured 5, flagged).

## Constraints

- Disk: ~16 GB free on the host. Keep one dialect's containers and snapshots on disk at a time; drop snapshots of finished dialects. S2L is PostgreSQL-only with 200-byte payloads.
- `main` applies row limits with `Statement.setMaxRows` (no `LIMIT` in SQL text); the SQL-level runner must reproduce this via JDBC, not by appending `LIMIT`.
- MySQL/MariaDB `TIMESTAMP` columns in the DDL are second-precision; the generator writes the values the dialect stores (ties increase there), which is realistic for those dialects.
