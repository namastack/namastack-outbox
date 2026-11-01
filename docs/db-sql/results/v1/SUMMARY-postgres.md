# v1: PostgreSQL measurement summary

Worktree `outbox-wt-v1`. Table `outbox_record`; `prepare` = fresh schema + `VACUUM (FREEZE, ANALYZE)`. Medians in ms, run 1 (after snapshot load) / run 2 (after container restart, other variant order). Plan shape = first parameter set, without the client row cap; `examined` = rows produced by scan nodes plus rows removed by filter. Full tables: `postgres/<scenario>/timings.csv`, plans in `plans/`.


## Key observations

- Autovacuum: the `prepare` VACUUM (FREEZE, ANALYZE) set `last_vacuum` in every run (`vacuum_count=1`); `last_autovacuum` is empty and `autovacuum_count=0` in every env.txt except S9. In S9 (`churn`, no manual VACUUM afterwards) autovacuum/autoanalyze ran during the churn phase and finished before the bench started (churn end to bench start < 1 s gap; the last autoanalyze is 0-90 s before the bench), no autovacuum started inside a bench window. S10/writecost have no env.txt; their schema was freshly prepared (VACUUM) before each run.
- Run-to-run noise is low (see `noise-postgres.md`); the single exception class is `main` find_completed_before on S4/S4-b100/S8-S2 (1.5 s vs 2.3-7 s, 5 reps, plan without LIMIT and a client row cap).
- Single-key sample parameters (S5: main and v1 sampled different keys, 1999 vs 2000 rows) are not guaranteed identical across variants; only S5 `find_incomplete_by_key`/`delete_by_key_status` are affected (1999 vs 2000 rows).
- v1: `LIMIT` bound in SQL. `keys_strict`/`oldest_*` use an index-only scan on the incomplete-ordered index; S4 keys_strict ~1.2 ms, S5 ~1.3 ms. `keys_nonstrict` is slower than main on S2/S8-S2/S7/S9 (2.2 vs 1.5 ms, 12.9 vs 8.9 ms on S7) in both runs. `find_completed_before` is ~3x faster (still a seq scan + sort on S2: ~490 ms, the slowest query of the healthy scenarios).
- Write cost S2 (committed, 6 indexes): insert p50 2.11 ms single thread, 1589/s with 4 threads (main: 1.79 ms / 1802/s, within the insert noise across runs); upd_complete committed p50 2.10 ms. Rolled-back `insert` on S2: 0.41/0.44 ms vs main 0.33/0.32 ms.
- S10: 1 poller + 500/s producer 333-361 rec/s processed; 4 pollers 512 rec/s (producer-bound); unthrottled 1 poller 291-301 rec/s, 4 pollers 1093-1096 rec/s.

## S1

9392 kB 9617408 2928 kB 2998272; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 7.78 | 8.86 | 1000 | Seq Scan[outbox_record] ret 7532.0x1 examined 10000.0 | Limit,Sort | buf hit 1169 read 0 | exec 4.715 ms |
| RET | ids_completed_before | 3.62 | 3.57 | 1000 | Seq Scan[outbox_record] ret 7532.0x1 examined 10000.0 | Limit,Sort | buf hit 1169 read 0 | exec 3.683 ms |
| RET | delete_by_ids | 3.31 | 3.31 | 1000 | Seq Scan[outbox_record] ret 1000.0x1 examined 10000.0 | buf hit 2169 read 0 | exec 2.321 ms |
| MON | oldest_due_strict | 1.10 | 1.15 | 1 | Index Scan[status_retry] ret 1.0x1 examined 1.0; Index Only Scan[created_id_incomplete] ret 0.0x1 examined 21, heapfetch 2 | Limit,Nested Loop | buf hit 8 read 0 | exec 0.076 ms |
| ADMIN | find_pending_all | 1.05 | 1.21 | 180 | Index Scan[status] ret 180.0x1 examined 180.0 | Sort | buf hit 27 read 0 | exec 0.194 ms |
| HOT | keys_nonstrict | 0.84 | 0.80 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 30.0, heapfetch 0; Index Only Scan[created_id_incomplete] ret 0.0x10 examined 260, heapfetch 0 | Limit,Nested Loop | buf hit 24 read 0 | exec 0.102 ms |

Write statements (rolled back, run1/run2 ms): insert 0.41/0.37, upd_complete 0.32/0.31, delete_by_id 0.16/0.20

## S2

907 MB 950681600 276 MB 289914880; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 493.98 | 489.47 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Limit,Sort | buf hit 15969 read 100121 | exec 140.241 ms |
| RET | ids_completed_before | 489.74 | 483.07 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Limit,Sort | buf hit 15968 read 100122 | exec 149.605 ms |
| MON | count_statuses_all | 38.38 | 38.54 | 3 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 254 | Aggregate,Aggregate | buf hit 936 read 0 | exec 52.861 ms |
| MON | count_completed | 28.61 | 28.88 | 1 | Index Only Scan[status] ret 328666.67x3 examined 986000.01, heapfetch 219 | Aggregate,Gather,Aggregate | buf hit 885 read 0 | exec 42.696 ms |
| RET | delete_by_status_failed | 10.79 | 11.02 | 12800 | Index Scan[status] ret 12800.0x1 examined 12800.0 | buf hit 25021 read 0 | exec 11.036 ms |
| HOT | keys_strict | 2.25 | 2.31 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 11638.0, heapfetch 0; Index Only Scan[record_key_completed_created_id] ret 0.0x10 examined 10, heapfetch 0 | Limit,Nested Loop | buf hit 247 read 0 | exec 1.15 ms |

Write statements (rolled back, run1/run2 ms): insert 0.41/0.44, upd_complete 0.31/0.36, delete_by_id 0.17/0.18

Write cost (committed, S2): insert 1 thread p50 2.107 ms p95 2.782 ms 451.1/s; 4 threads p50 2.011 ms 1588.8/s; upd_complete committed p50 2.104 ms p95 2.645 ms. Sizes before/after: 907 MB 950591488 276 MB 289898496 -> 916 MB 960487424 283 MB 296411136

Concurrency S10 (run1 / run2; processed rec/s, inserted rec/s, keys p50 ms, pages p50 ms, update p50 ms, insert p50 ms):

| config | processed/s | inserted/s | keys p50 | pages p50 | update p50 | insert p50 |
|---|---:|---:|---:|---:|---:|---:|
| p1-r0 | 290.6 / 300.9 | 1680.5 / 1760.6 | 6.52 / 6.74 | 0.70 / 0.71 | 1.82 / 1.83 | 1.79 / 1.80 |
| p1-r500 | 361.2 / 332.6 | 496.7 / 494.5 | 6.37 / 6.52 | 0.70 / 0.73 | 1.78 / 1.79 | 1.55 / 1.70 |
| p4-r0 | 1095.8 / 1093.0 | 1631.0 / 1615.8 | 8.29 / 7.87 | 0.90 / 0.84 | 2.01 / 2.00 | 1.87 / 1.89 |
| p4-r500 | 512.0 / 512.1 | 496.6 / 496.5 | 4.30 / 4.09 | 0.49 / 0.47 | 1.65 / 1.69 | 1.51 / 1.56 |

## S8-S2

907 MB 950689792 276 MB 289923072; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 494.30 | 492.91 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Limit,Sort | buf hit 15968 read 100123 | exec 133.804 ms |
| RET | ids_completed_before | 489.80 | 484.44 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Limit,Sort | buf hit 15970 read 100121 | exec 132.479 ms |
| MON | count_statuses_all | 38.63 | 38.67 | 3 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 235 | Aggregate,Aggregate | buf hit 922 read 0 | exec 52.231 ms |
| MON | count_completed | 29.04 | 28.92 | 1 | Index Only Scan[status] ret 328666.67x3 examined 986000.01, heapfetch 133 | Aggregate,Gather,Aggregate | buf hit 874 read 0 | exec 42.617 ms |
| RET | delete_by_status_failed | 10.83 | 11.38 | 12800 | Index Scan[status] ret 12800.0x1 examined 12800.0 | buf hit 25021 read 0 | exec 11.155 ms |
| HOT | keys_strict | 2.06 | 2.17 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 12842.0, heapfetch 0; Index Only Scan[record_key_completed_created_id] ret 0.0x10 examined 10, heapfetch 0 | Limit,Nested Loop | buf hit 265 read 0 | exec 1.363 ms |

Write statements (rolled back, run1/run2 ms): insert 0.36/0.39, upd_complete 0.38/0.37, delete_by_id 0.21/0.19

## S3

5064 kB 5185536 2104 kB 2154496; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| ADMIN | find_pending_all | 11.52 | 12.58 | 3500 | Index Scan[status] ret 3500.0x1 examined 3500.0 | Sort | buf hit 434 read 0 | exec 2.808 ms |
| ADMIN | find_failed_all | 5.00 | 4.51 | 1500 | Index Scan[status] ret 1500.0x1 examined 1500.0 | Sort | buf hit 220 read 0 | exec 1.367 ms |
| HOT | keys_strict | 1.71 | 1.49 | 10 | Index Only Scan[created_id_incomplete] ret 21.0x1 examined 1521.0, heapfetch 0; Index Only Scan[record_key_completed_created_id] ret 0.52x21 examined 31.92, heapfetch 0 | Limit,Nested Loop | buf hit 71 read 0 | exec 0.262 ms |
| HOT | keys_nonstrict | 1.40 | 1.44 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 600.0, heapfetch 0; Index Scan[record_key_completed_created_id] ret 0.0x10 examined 20 | Limit,Nested Loop | buf hit 44 read 0 | exec 0.156 ms |
| MON | count_partitions | 1.18 | 1.17 | 1 | Index Only Scan[partition_status_retry] ret 3500.0x1 examined 3500.0, heapfetch 44 | Aggregate | buf hit 65 read 0 | exec 0.613 ms |

Write statements (rolled back, run1/run2 ms): insert 0.30/0.39, upd_complete 0.27/0.23, delete_by_id 0.13/0.17

## S4

1772 MB 1857863680 656 MB 688242688; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 491.67 | 491.78 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 731 read 116461 | exec 240.977 ms |
| RET | ids_completed_before | 483.40 | 484.51 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 722 read 116461 | exec 240.506 ms |
| MON | count_statuses_all | 70.83 | 70.67 | 2 | Index Only Scan[status] ret 666666.67x3 examined 2000000.0100000002, heapfetch 112 | Aggregate,Aggregate | buf hit 1745 read 0 | exec 105.856 ms |
| MON | count_partitions | 63.72 | 65.03 | 1 | Index Only Scan[partition_status_retry] ret 1000000.0x1 examined 1000000.0, heapfetch 44 | Aggregate | buf hit 6661 read 0 | exec 104.845 ms |
| MON | count_statuses | 38.06 | 38.10 | 1 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 48 | Aggregate,Aggregate | buf hit 873 read 0 | exec 52.716 ms |
| HOT | keys_strict | 1.18 | 1.22 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 10.0, heapfetch 0; Index Only Scan[record_key_completed_created_id] ret 0.0x10 examined 10, heapfetch 0 | Limit,Nested Loop | buf hit 45 read 0 | exec 0.133 ms |

Write statements (rolled back, run1/run2 ms): insert 0.45/0.45, upd_complete 0.36/0.35, delete_by_id 0.18/0.19

## S4-b100

1772 MB 1857863680 656 MB 688242688; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 488.51 | 491.55 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 732 read 116461 | exec 241.08 ms |
| RET | ids_completed_before | 485.52 | 491.75 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 728 read 116461 | exec 237.833 ms |
| MON | count_statuses_all | 70.56 | 71.25 | 2 | Index Only Scan[status] ret 666666.67x3 examined 2000000.0100000002, heapfetch 112 | Aggregate,Aggregate | buf hit 1745 read 0 | exec 106.741 ms |
| MON | count_partitions | 63.57 | 62.92 | 1 | Index Only Scan[partition_status_retry] ret 1000000.0x1 examined 1000000.0, heapfetch 44 | Aggregate | buf hit 6661 read 0 | exec 103.851 ms |
| MON | count_statuses | 37.84 | 38.13 | 1 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 48 | Aggregate,Aggregate | buf hit 873 read 0 | exec 51.841 ms |
| HOT | pages | 1.84 | 1.94 | 100 | Function Scan[] ret 100.0x1 examined 100.0; Index Scan[record_key_completed_created_id] ret 1.0x100 examined 100.0 | Nested Loop,Sort | buf hit 500 read 0 | exec 0.826 ms |

Write statements (rolled back, run1/run2 ms): insert 0.44/0.51, upd_complete 0.38/0.45, delete_by_id 0.25/0.20

## S8-S4

1772 MB 1857863680 656 MB 688242688; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 487.25 | 492.06 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 729 read 116461 | exec 237.95 ms |
| RET | ids_completed_before | 482.30 | 491.82 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Limit,Sort | buf hit 720 read 116461 | exec 238.015 ms |
| MON | count_statuses_all | 70.58 | 71.28 | 2 | Index Only Scan[status] ret 666666.67x3 examined 2000000.0100000002, heapfetch 228 | Aggregate,Aggregate | buf hit 1756 read 0 | exec 105.973 ms |
| MON | count_statuses | 38.48 | 38.19 | 1 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 109 | Aggregate,Aggregate | buf hit 879 read 0 | exec 51.995 ms |
| MON | count_new | 29.09 | 28.98 | 1 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 109 | Aggregate,Gather,Aggregate | buf hit 873 read 0 | exec 43.536 ms |
| HOT | keys_nonstrict | 1.01 | 1.06 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 33.0, heapfetch 0; Index Scan[record_key_completed_created_id] ret 0.0x10 examined 10 | Limit,Nested Loop | buf hit 54 read 0 | exec 0.1 ms |

Write statements (rolled back, run1/run2 ms): insert 0.57/0.48, upd_complete 0.55/0.48, delete_by_id 0.18/0.23

## S5

445 MB 466272256 159 MB 166658048; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 154.83 | 152.07 | 1000 | Index Scan[status] ret 81684.67x3 examined 299999.01 | Limit,Sort | buf hit 232 read 34933 | exec 78.013 ms |
| RET | ids_completed_before | 150.62 | 149.41 | 1000 | Index Scan[status] ret 81684.67x3 examined 299999.01 | Limit,Sort | buf hit 226 read 34934 | exec 76.972 ms |
| HOT | pages | 134.81 | 135.01 | 20000 | Function Scan[] ret 10.0x1 examined 10.0; Bitmap Heap Scan[outbox_record] ret 2000.0x10 examined 20000.0; Bitmap Index Scan[record_key_completed_created_id] ret 2000.0x10 examined 20000.0; Bitmap Index Scan[status] ret 199980.0x10 examined 1999800.0 | Nested Loop,Sort,Sort | buf hit 20915 read 0 | exec 92.187 ms |
| MON | count_statuses_all | 27.20 | 27.16 | 3 | Index Only Scan[status] ret 250000.0x2 examined 500000.0, heapfetch 109 | Aggregate,Aggregate | buf hit 459 read 0 | exec 41.598 ms |
| MON | count_statuses | 19.21 | 19.02 | 2 | Index Only Scan[status] ret 100000.0x2 examined 200000.0, heapfetch 44 | Aggregate,Aggregate | buf hit 190 read 0 | exec 27.299 ms |

Write statements (rolled back, run1/run2 ms): insert 0.36/0.37, upd_complete 0.30/0.32, delete_by_id 0.16/0.14

## S6

945 MB 990412800 297 MB 311148544; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 397.89 | 399.07 | 1000 | Index Scan[status] ret 205592.67x3 examined 800000.01 | Limit,Sort | buf hit 578 read 93578 | exec 199.959 ms |
| RET | ids_completed_before | 394.06 | 394.47 | 1000 | Index Scan[status] ret 205592.67x3 examined 800000.01 | Limit,Sort | buf hit 602 read 93578 | exec 203.162 ms |
| MON | count_statuses_all | 41.02 | 40.71 | 2 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 741 | Aggregate,Aggregate | buf hit 1022 read 0 | exec 56.028 ms |
| MON | count_completed | 26.36 | 26.33 | 1 | Index Only Scan[status] ret 266666.67x3 examined 800000.01, heapfetch 245 | Aggregate,Gather,Aggregate | buf hit 752 read 0 | exec 37.853 ms |
| MON | count_statuses | 21.30 | 21.20 | 1 | Index Only Scan[status] ret 100000.0x2 examined 200000.0, heapfetch 496 | Aggregate,Aggregate | buf hit 268 read 0 | exec 23.457 ms |
| HOT | keys_nonstrict | 1.25 | 1.21 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 376.0, heapfetch 0; Index Scan[record_key_completed_created_id] ret 0.0x10 examined 10 | Limit,Nested Loop | buf hit 60 read 0 | exec 0.13 ms |

Write statements (rolled back, run1/run2 ms): insert 0.36/0.38, upd_complete 0.35/0.37, delete_by_id 0.16/0.17

## S7

973 MB 1020420096 324 MB 340205568; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | delete_by_status_failed | 14538.85 (slow(>2s)) | 15196.09 | 390000 | Seq Scan[outbox_record] ret 390000.0x1 examined 1000000.0 | buf hit 390000 read 124525 | exec 14480.316 ms |
| RET | ids_completed_before | 243.75 | 239.81 | 1000 | Seq Scan[outbox_record] ret 189714.67x3 examined 1000001.01 | Limit,Sort | buf hit 16083 read 108518 | exec 124.448 ms |
| RET | find_completed_before | 238.36 | 242.81 | 1000 | Seq Scan[outbox_record] ret 189714.67x3 examined 1000001.01 | Limit,Sort | buf hit 14413 read 110188 | exec 143.751 ms |
| MON | count_statuses_all | 42.86 | 43.01 | 3 | Index Only Scan[status] ret 333333.33x3 examined 999999.99, heapfetch 340 | Aggregate,Aggregate | buf hit 998 read 0 | exec 59.987 ms |
| MON | count_statuses | 26.62 | 26.43 | 2 | Index Only Scan[status] ret 200000.0x2 examined 400000.0, heapfetch 141 | Aggregate,Aggregate | buf hit 427 read 0 | exec 34.526 ms |
| HOT | keys_strict | 13.08 | 13.13 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 133913.0, heapfetch 0; Index Only Scan[record_key_completed_created_id] ret 0.0x10 examined 10, heapfetch 0 | Limit,Nested Loop | buf hit 2330 read 0 | exec 12.935 ms |

Write statements (rolled back, run1/run2 ms): insert 0.33/0.43, upd_complete 0.35/0.31, delete_by_id 0.14/0.18

## S9

1691 MB 1772650496 604 MB 633200640; autovacuum during bench: last_autovacuum=2026-11-01 03:59:13.516661+00, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 410.80 | 434.69 | 1000 | Seq Scan[outbox_record] ret 90237.0x3 examined 853239.0 | Limit,Sort | buf hit 15542 read 200858 | exec 173.151 ms |
| RET | ids_completed_before | 409.75 | 423.25 | 1000 | Seq Scan[outbox_record] ret 90237.0x3 examined 853239.0 | Limit,Sort | buf hit 15543 read 200857 | exec 169.987 ms |
| RET | delete_by_status_failed | 150.34 | 141.27 | 22827 | Index Scan[status] ret 22827.0x1 examined 22827.0 | buf hit 22835 read 21678 | exec 171.427 ms |
| MON | count_statuses_all | 36.18 | 39.09 | 3 | Index Only Scan[status] ret 284413.33x3 examined 853239.99, heapfetch 4439 | Aggregate,Aggregate | buf hit 1927 read 0 | exec 48.506 ms |
| MON | count_completed | 26.82 | 27.43 | 1 | Index Only Scan[status] ret 276404.33x3 examined 829212.99, heapfetch 3707 | Aggregate,Gather,Aggregate | buf hit 1218 read 0 | exec 39.055 ms |
| HOT | keys_strict | 2.54 | 3.75 | 10 | Index Only Scan[created_id_incomplete] ret 10.0x1 examined 11638.0, heapfetch 668; Index Only Scan[record_key_completed_created_id] ret 0.0x10 examined 10, heapfetch 0 | Limit,Nested Loop | buf hit 880 read 0 | exec 1.442 ms |

Write statements (rolled back, run1/run2 ms): insert 0.30/0.32, upd_complete 0.34/0.35, delete_by_id 0.13/0.18

Churn (S9, 500k inserts, no manual VACUUM after): ; before 907 MB 950591488 276 MB 289898496; after 1691 MB 1772650496 604 MB 633200640; churn_seconds: 532; pg_stat after (live, dead, last_vacuum, last_autovacuum, last_autoanalyze, vac, autovac): 849707 | 48686 | 2026-11-01 03:51:32.987528+00 | 2026-11-01 03:59:13.516661+00 | 2026-11-01 03:59:14.666605+00 | 1 | 4

## Repository level (run 1; median ms, slowest 4 methods)

- S1 jdbc: find_completed_before 10.20, delete_completed_batch 7.59, insert 2.62, delete_by_id 2.14
- S1 jpa: find_completed_before 13.14, delete_completed_batch 8.98, keys_strict 3.14, delete_by_id 2.81
- S2 jdbc: delete_completed_batch 505.50, find_completed_before 499.70, count_completed 24.70, delete_by_status_failed 11.15
- S2 jpa: find_completed_before 267.64, delete_completed_batch 229.06, count_completed 103.69, count_failed 22.13
- S4 jdbc: delete_completed_batch 494.35, find_completed_before 486.80, count_partitions 63.88, count_statuses 26.88
- S4 jpa: find_completed_before 277.46, delete_completed_batch 234.32, count_partitions 211.05, count_statuses 196.62
- S5 jdbc: delete_completed_batch 160.80, find_completed_before 153.91, pages 127.46, count_statuses 22.77
- S5 jpa: pages 209.06, find_completed_before 125.33, count_completed 120.75, delete_completed_batch 108.57
