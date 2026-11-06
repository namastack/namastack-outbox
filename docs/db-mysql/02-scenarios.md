# Scenario matrix (MySQL study)

Copied from the PostgreSQL study (through the MariaDB study) so all studies use **identical data** (same generator, seed, row counts, status mixes, key
distributions, parameters). Only the dialect matrix, the S9 metrics, the Priority 3 exclusion and the constraints are adapted to MySQL. Do not change a
scenario definition; add a new scenario id instead if one is needed, and record it in `STATE.md`. All scenarios are deterministic
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
| `payload` | JSON, **500 bytes** (S2L: 200 bytes). `context` JSON ~100 bytes or NULL (50 %). |
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
| **S2L** | Same, 10M (payload 200 B) | 10 000 000 | same ratios (NEW due 1 000, NEW future 200, FAILED 128 000) | 2 000 000 keys | 200 | 256 | off | none. **Not run on this host** (needs ≥ 30 GB free disk; the host has ~22 GB). Kept for id stability. |
| **S3** | Retention on, small table | 5 000 live rows | 3 000 NEW due, 500 NEW future, 1 500 FAILED, **0 COMPLETED** (`delete-completed-records=true`) | 4 000 keys | 500 | 256 | delete-on-complete (`deleteById`) | write group: insert + `deleteById` cycle (table size stays constant) |
| **S4** | Backlog drain | 2 000 000 | 1 000 000 NEW due + 1 000 000 COMPLETED (older) | NEW: 1 record per key (1M keys); COMPLETED: other 500 000 keys × 2 | none | 256 | off | key query at batchSize 10 and 100; pages at 10 and 100 keys |
| **S5** | Hot keys / head-of-line blocking | 500 000 | 200 000 NEW (100 keys × 2 000), 300 000 COMPLETED (same 100 keys, older) | 100 hot keys; of those 20 have a FAILED head, 20 a NEW head with future retry, 60 a due head | 20 heads future | 256 | off | strict and non-strict |
| **S6** | Retry heavy | 1 000 000 | 800 000 COMPLETED, 195 000 NEW future (`T0 + 1 min..1 h`), 5 000 NEW due | 150 000 NEW keys, 1–2 NEW per key, plus completed history | 195 000 | 256 | off | none |
| **S7** | Many FAILED | 1 000 000 | 600 000 COMPLETED, 390 000 FAILED (`next_retry_at` spread over `T0 - 90 d .. T0`), 10 000 NEW due | 300 000 keys | none | 256 | off (the retention queries of S7 are Priority 3 and are not run in this study) | none |
| **S8** | Partial ownership | S2 data and S4 data | as S2 / S4 | as S2 / S4 | as S2 / S4 | **64 of 256** (`p % 4 == 0`) | off | parameter set only, no new data |
| **S9** | Churn / fragmentation | starts as S2 (1M) | after churn: same mix as S2 | as S2 | as S2 | 256 | delete-on-complete for 30 % of keys (`deleteById`, P1); the rest of the history is removed by a **harness-internal** delete (fixed SQL, identical for every variant, by primary key, not the variant's Priority 3 statements, not timed) | churn phase: 500 000 inserts, each processed (1 update; 10 % 1–3 retry updates; 2 % FAILED), ~500 000 oldest COMPLETED removed. InnoDB at defaults, **no manual `ANALYZE`/`OPTIMIZE`** after churn. Record table and per-index sizes before/after, history list length (`information_schema.INNODB_METRICS` `trx_rseg_history_len`, purge lag), and the timings of the P1/P2 queries after churn. |
| **S10** | Concurrency | S2 data | as S2 | as S2 | as S2 | 1 poller × 256, 4 pollers × 64 (disjoint) | off | 60 s run, processing loop (find keys → pages → `updateProcessingState` complete) with a producer inserting **500 rec/s** (and a second run with an unthrottled producer, 4 threads). Report processed rec/s, key-query and pages p50/p95, insert p50/p95. |

## Query groups and ids

The harness assigns ids. A query not applicable to a variant (e.g. `countByStatuses` on main) is reported as `n/a`.

| Group | Priority | Query ids | Run in this study |
|---|---|---|---|
| **HOT** | P1 | `keys_strict` (findRecordKeysInPartitions, strict), `keys_nonstrict`, `pages` (findIncompleteRecordPages / findByKeysAndStatus) | timed, plans, equivalence |
| **WRITE** | P1 | `insert` (create), `upd_complete`, `upd_retry`, `upd_fail` (updateProcessingState), `delete_by_id` | timed, writecost, equivalence |
| **MON** | P2 (+ non-targets) | `count_statuses` (v1 countByStatuses, NEW+FAILED), `count_partitions` (countRecordsByPartitions, NEW), `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` are the P2 targets. `count_new`, `count_failed`, `count_completed` (countByStatus), `count_statuses_all` (all three statuses) and `count_partition_single` (deprecated metrics module) are measured with the group but are **not targets** | timed, plans, equivalence |
| **RET** | P3 | `find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before`, `delete_by_ids`, `delete_by_status_failed`, `delete_by_key_status` | **never run** (no timing, no plans, no equivalence, no repository-level calls) |
| **ADMIN** | P3 | `find_pending_all`, `find_failed_all`, `find_incomplete_by_key` | **never run** |

**Priority 3 is excluded from every run of this study** (user decision 2026-11-06): every `bench`, `equiv`, `repo-bench` and driver call
restricts itself to `HOT,WRITE,MON`. Priority 3 SQL of the MySQL dialect is not changed by this study, so it needs no measurement; its
correctness stays covered by the project's own test suite.

Monitoring frequency weights for interpretation (from the usage map): HOT key query 0.5/s/instance even when idle; MON 6 queries per 15 s per instance.

## Labels

| Label | Data | Parameters |
|---|---|---|
| `S1`, `S2`, `S4`, `S5`, `S6`, `S7` | own snapshot | all 256 partitions |
| `S8-S2`, `S8-S4`, `S8-S5`, `S8-S6`, `S8-S7` | S2 / S4 / S5 / S6 / S7 snapshot | `--owned mod4` (64 partitions) |
| `S16-S2`, `S16-S4` | S2 / S4 snapshot | `--owned 0,16,32,...,240` (16 partitions, 16 instances) |
| `S4-b100` | S4 snapshot | `--batch 100` |
| `S2-wc`, `S4-wc` | S2 / S4 snapshot | `writecost` (committed insert / `upd_complete`, 1 and 4 threads, sizes, InnoDB redo/page counters) |
| `S9` | S2 snapshot + `churn` | 256 partitions |
| `S10-S2-p1`, `S10-S2-p4`, `S10-S4-p4` (+ `-unthr`) | S2 / S4 snapshot | `concurrency --pollers 1|4 --seconds 60 --rate 500` (and `--rate 0`) |

**Partial ownership (`S8-*`, `S16-*`) is the default deployment** and ranks first in every verdict (ADR-0005). Full ownership is the
secondary case.

## MySQL matrix

| Stage | Labels |
|---|---|
| Screen (fast, P1/P2 only) | `S8-S2`, `S8-S4`, `S8-S5`, `S8-S7`, + `S2-wc` when DDL changes |
| Confirm | every label above except `S2L`, equivalence on `S1`, `S5`, `S7` |
| Final comparison (main / branch / final) | every label except `S2L`, two passes in reversed order, equivalence on `S1`, `S2`, `S5`, `S7`, buffer-pool and server-version sensitivity runs (see orchestrator prompt) |

Timings cover the **HOT, WRITE and MON groups** (`--groups HOT,WRITE,MON`). RET and ADMIN (Priority 3) are not run at all, not even
in the equivalence check (`equiv --groups HOT,WRITE,MON`).

Repetitions: warmup 5, measured 20 (queries with median > 2 s: warmup 1, measured 5, flagged; > 30 s: one execution).

## Constraints

- Host (2026-11-06): bare metal, Intel Core i7-8700K (6 cores / 12 threads, one L3), 15 GiB RAM, ~22 GB free disk. Docker containers
  `--cpus=4 --memory=4g` as in the PostgreSQL and MariaDB studies, pinned to lane 1 (`harness/bin/lane.sh`); **one measuring lane**.
  Absolute timings are not comparable with the other studies (different machines); only relative results within this study are.
- Disk: the lane holds one snapshot plus one run schema (two during equivalence). Keep at least 8 GB free; drop run schemas between
  scenarios (`bench dropruns`). MySQL writes a binary log by default (`log_bin=ON`): the harness purges binary logs after `gen`,
  `prepare`, `churn`, `concurrency` and `writecost` so loads do not fill the disk (binary logging itself stays at the image default).
- `main` limits rows via `Statement.setMaxRows`; the SQL-level runner reproduces this via MySQL Connector/J (the driver applications
  use with MySQL), not by appending `LIMIT`. The branch's MySQL SQL binds `LIMIT :limit` where it has a limit clause.
- The MySQL DDL uses `TIMESTAMP` columns (second precision); the generator writes the values the dialect stores (more `created_at`
  ties, so the `id` tie-break matters more than on PostgreSQL). Column types do not change (ADR-0005 non-goal).
