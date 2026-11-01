# main: PostgreSQL measurement summary

Worktree `outbox-wt-main`. Table `outbox_record`; `prepare` = fresh schema + `VACUUM (FREEZE, ANALYZE)`. Medians in ms, run 1 (after snapshot load) / run 2 (after container restart, other variant order). Plan shape = first parameter set, without the client row cap; `examined` = rows produced by scan nodes plus rows removed by filter. Full tables: `postgres/<scenario>/timings.csv`, plans in `plans/`.


## Key observations

- Autovacuum: the `prepare` VACUUM (FREEZE, ANALYZE) set `last_vacuum` in every run (`vacuum_count=1`); `last_autovacuum` is empty and `autovacuum_count=0` in every env.txt except S9. In S9 (`churn`, no manual VACUUM afterwards) autovacuum/autoanalyze ran during the churn phase and finished before the bench started (churn end to bench start < 1 s gap; the last autoanalyze is 0-90 s before the bench), no autovacuum started inside a bench window. S10/writecost have no env.txt; their schema was freshly prepared (VACUUM) before each run.
- Run-to-run noise is low (see `noise-postgres.md`); the single exception class is `main` find_completed_before on S4/S4-b100/S8-S2 (1.5 s vs 2.3-7 s, 5 reps, plan without LIMIT and a client row cap).
- Single-key sample parameters (S5: main and v1 sampled different keys, 1999 vs 2000 rows) are not guaranteed identical across variants; only S5 `find_incomplete_by_key`/`delete_by_key_status` are affected (1999 vs 2000 rows).
- main: no `LIMIT` in SQL (driver `setMaxRows` only). On S4/S5/S6/S7 (many due/NEW rows) `keys_strict`, `keys_nonstrict`, `oldest_created` and `oldest_due_strict` scan or sort large parts of the table; S4 keys_strict ~4.3 s, S5 ~14.6 s, S5 oldest_due_strict ~6 s. Counts (`count_completed` etc.) and `find_completed_before` (1.5 s on S2, seq scan + top-N sort of 1M rows) are the slowest on the healthy scenarios.
- Write cost S2 (committed, 6 indexes): insert p50 1.79 ms single thread, 1802/s with 4 threads; upd_complete committed p50 2.07 ms.
- S10: 1 poller + 500/s producer 162-170 rec/s processed; 4 pollers 512-621 rec/s; unthrottled 1 poller 49-50 rec/s (keys query p50 ~95 ms).

## S1

9392 kB 9617408 3080 kB 3153920; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 15.51 | 15.88 | 1000 | Seq Scan[outbox_record] ret 7532.0x1 examined 10000.0 | Sort (disk) | buf hit 1169 read 0 | exec 13.652 ms |
| RET | ids_completed_before | 3.92 | 3.72 | 1000 | Seq Scan[outbox_record] ret 7532.0x1 examined 10000.0 | Sort | buf hit 1169 read 0 | exec 4.065 ms |
| RET | delete_by_ids | 3.30 | 3.65 | 1000 | Seq Scan[outbox_record] ret 1000.0x1 examined 10000.0 | buf hit 2169 read 0 | exec 2.066 ms |
| HOT | keys_strict | 1.83 | 1.98 | 10 | Index Scan[status] ret 150.0x1 examined 180.0; Index Scan[record_key_completed_created] ret 0.0x150 examined 150 | Sort,Aggregate,Sort,Nested Loop | buf hit 476 read 0 | exec 0.672 ms |
| MON | oldest_due_strict | 1.44 | 1.44 | 1 | Index Scan[status] ret 150.0x1 examined 180.0; Index Scan[record_key_completed_created] ret 0.0x150 examined 150 | Aggregate,Nested Loop | buf hit 520 read 0 | exec 0.457 ms |

Write statements (rolled back, run1/run2 ms): insert 0.32/0.43, upd_complete 0.32/0.38, delete_by_id 0.17/0.15

## S2

907 MB 950681600 295 MB 309043200; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 1523.29 | 1532.74 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Sort (disk) | buf hit 15964 read 100126 | exec 1055.411 ms |
| RET | ids_completed_before | 647.18 | 641.22 | 1000 | Seq Scan[outbox_record] ret 770711.0x1 examined 1000000.0 | Sort (disk) | buf hit 16172 read 99842 | exec 520.54 ms |
| MON | count_completed | 28.71 | 28.53 | 1 | Index Only Scan[status] ret 328666.67x3 examined 986000.01, heapfetch 219 | Aggregate,Gather,Aggregate | buf hit 885 read 0 | exec 43.518 ms |
| RET | delete_by_status_failed | 10.85 | 11.05 | 12800 | Index Scan[status] ret 12800.0x1 examined 12800.0 | buf hit 25021 read 0 | exec 11.131 ms |
| HOT | keys_strict | 5.41 | 5.45 | 10 | Index Scan[status] ret 1000.0x1 examined 1200.0; Index Scan[record_key_completed_created] ret 0.05x1000 examined 1050.0 | Sort,Aggregate,Sort,Nested Loop | buf hit 4162 read 0 | exec 4.843 ms |

Write statements (rolled back, run1/run2 ms): insert 0.33/0.32, upd_complete 0.41/0.34, delete_by_id 0.19/0.15

Write cost (committed, S2): insert 1 thread p50 1.786 ms p95 2.535 ms 526.6/s; 4 threads p50 1.793 ms 1802.2/s; upd_complete committed p50 2.073 ms p95 2.579 ms. Sizes before/after: 907 MB 950591488 295 MB 309043200 -> 916 MB 960479232 299 MB 313507840

Concurrency S10 (run1 / run2; processed rec/s, inserted rec/s, keys p50 ms, pages p50 ms, update p50 ms, insert p50 ms):

| config | processed/s | inserted/s | keys p50 | pages p50 | update p50 | insert p50 |
|---|---:|---:|---:|---:|---:|---:|
| p1-r0 | 49.1 / 49.9 | 2335.4 / 2062.2 | 94.64 / 96.47 | 1.27 / 1.33 | 1.66 / 1.81 | 1.41 / 1.54 |
| p1-r500 | 169.5 / 161.8 | 496.0 / 496.7 | 33.90 / 38.38 | 0.78 / 0.81 | 1.66 / 1.64 | 1.04 / 1.03 |
| p4-r0 | 529.4 / 621.3 | 2292.2 / 2125.5 | 34.79 / 26.59 | 1.47 / 1.17 | 1.45 / 1.54 | 1.36 / 1.48 |
| p4-r500 | 511.9 / 512.1 | 496.6 / 496.6 | 2.68 / 2.70 | 1.02 / 1.06 | 1.80 / 1.85 | 1.58 / 1.64 |

## S8-S2

907 MB 950689792 295 MB 309043200; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 1522.03 | 2321.03 | 1000 | Seq Scan[outbox_record] ret 256903.67x3 examined 1000001.01 | Sort (disk) | buf hit 15926 read 100175 | exec 2826.606 ms |
| RET | ids_completed_before | 647.48 | 640.18 | 1000 | Seq Scan[outbox_record] ret 770711.0x1 examined 1000000.0 | Sort (disk) | buf hit 16172 read 99843 | exec 532.727 ms |
| MON | count_completed | 28.99 | 28.75 | 1 | Index Only Scan[status] ret 328666.67x3 examined 986000.01, heapfetch 133 | Aggregate,Gather,Aggregate | buf hit 874 read 0 | exec 42.721 ms |
| RET | delete_by_status_failed | 11.12 | 10.83 | 12800 | Index Scan[status] ret 12800.0x1 examined 12800.0 | buf hit 25021 read 0 | exec 22.353 ms |
| RET | delete_by_ids | 4.56 | 4.49 | 1000 | Bitmap Heap Scan[outbox_record] ret 1000.0x1 examined 1000.0; Bitmap Index Scan[outbox_record_pkey] ret 1000.0x1 examined 1000.0 | buf hit 3960 read 0 | exec 3.36 ms |
| HOT | keys_strict | 1.96 | 1.97 | 10 | Index Scan[status] ret 229.0x1 examined 1200.0; Index Scan[record_key_completed_created] ret 0.03x229 examined 235.87 | Sort,Aggregate,Sort,Nested Loop | buf hit 1078 read 0 | exec 1.421 ms |

Write statements (rolled back, run1/run2 ms): insert 0.45/0.34, upd_complete 0.37/0.32, delete_by_id 0.22/0.15

## S3

5064 kB 5185536 1544 kB 1581056; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| ADMIN | find_pending_all | 12.04 | 11.53 | 3500 | Index Scan[status] ret 3500.0x1 examined 3500.0 | Sort | buf hit 434 read 0 | exec 3.362 ms |
| HOT | keys_strict | 5.17 | 5.10 | 10 | Seq Scan[outbox_record] ret 5000.0x1 examined 5000.0; Index Scan[status] ret 3000.0x1 examined 3500.0 | Sort,Aggregate,Hash Join | buf hit 1049 read 0 | exec 5.36 ms |
| ADMIN | find_failed_all | 4.69 | 5.29 | 1500 | Index Scan[status] ret 1500.0x1 examined 1500.0 | Sort | buf hit 220 read 0 | exec 1.215 ms |
| MON | oldest_due_strict | 4.02 | 4.02 | 1 | Seq Scan[outbox_record] ret 5000.0x1 examined 5000.0; Index Scan[status] ret 3000.0x1 examined 3500.0 | Aggregate,Hash Join | buf hit 1062 read 0 | exec 3.593 ms |
| HOT | keys_nonstrict | 2.83 | 2.84 | 10 | Index Scan[status] ret 3000.0x1 examined 3500.0 | Sort,Aggregate | buf hit 434 read 0 | exec 2.508 ms |

Write statements (rolled back, run1/run2 ms): insert 0.35/0.35, upd_complete 0.33/0.33, delete_by_id 0.17/0.15

## S4

1772 MB 1857863680 573 MB 600743936; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| HOT | keys_strict | 4253.64 (slow(>2s)) | 4260.66 | 10 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 333333.33x3 examined 999999.99 | Sort (disk),Aggregate,Sort (disk),Hash Join | buf hit 16677 read 323068 | exec 2549.108 ms |
| RET | find_completed_before | 1530.93 (slow(>2s)) | 7064.43 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Sort (disk) | buf hit 717 read 116461 | exec 1023.572 ms |
| HOT | keys_nonstrict | 1345.52 | 1348.58 | 10 | Index Scan[status] ret 1000000.0x1 examined 1000000.0 | Sort (disk),Aggregate | buf hit 0 read 112337 | exec 1577.448 ms |
| MON | oldest_due_strict | 687.12 | 691.57 | 1 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 333333.33x3 examined 999999.99 | Aggregate,Gather,Aggregate,Hash Join | buf hit 16689 read 323084 | exec 744.344 ms |
| RET | ids_completed_before | 637.41 | 643.18 | 1000 | Index Scan[status] ret 772168.0x1 examined 1000000.0 | Sort (disk) | buf hit 0 read 116456 | exec 779.719 ms |

Write statements (rolled back, run1/run2 ms): insert 0.30/0.56, upd_complete 0.36/0.55, delete_by_id 0.16/0.31

## S4-b100

1772 MB 1857863680 573 MB 600743936; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| HOT | keys_strict | 4272.20 (slow(>2s)) | 4264.71 | 100 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 333333.33x3 examined 999999.99 | Sort (disk),Aggregate,Sort (disk),Hash Join | buf hit 16679 read 323067 | exec 2529.234 ms |
| RET | find_completed_before | 2291.59 | 1563.23 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Sort (disk) | buf hit 711 read 116460 | exec 1041.871 ms |
| HOT | keys_nonstrict | 1341.86 | 1352.38 | 100 | Index Scan[status] ret 1000000.0x1 examined 1000000.0 | Sort (disk),Aggregate | buf hit 0 read 112337 | exec 1578.976 ms |
| MON | oldest_due_strict | 696.36 | 693.12 | 1 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 333333.33x3 examined 999999.99 | Aggregate,Gather,Aggregate,Hash Join | buf hit 16684 read 323083 | exec 747.884 ms |
| RET | ids_completed_before | 639.98 | 645.22 | 1000 | Index Scan[status] ret 772168.0x1 examined 1000000.0 | Sort (disk) | buf hit 0 read 116456 | exec 782.566 ms |

Write statements (rolled back, run1/run2 ms): insert 0.58/0.58, upd_complete 0.49/0.58, delete_by_id 0.31/0.36

## S8-S4

1772 MB 1857863680 573 MB 600752128; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| HOT | keys_strict | 2881.26 | 2851.09 | 10 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 83605.0x3 examined 999999.0 | Sort (disk),Aggregate,Sort (disk),Hash Join | buf hit 16711 read 323067 | exec 1011.657 ms |
| RET | find_completed_before | 1557.73 | 1532.19 | 1000 | Index Scan[status] ret 257389.33x3 examined 999999.99 | Sort (disk) | buf hit 721 read 116461 | exec 1489.572 ms |
| HOT | keys_nonstrict | 1429.56 | 1431.85 | 10 | Index Scan[status] ret 250815.0x1 examined 1000000.0 | Sort (disk),Aggregate | buf hit 0 read 112337 | exec 718.086 ms |
| MON | oldest_due_strict | 848.99 | 844.33 | 1 | Seq Scan[outbox_record] ret 333333.33x3 examined 1999998.99; Index Scan[status] ret 83605.0x3 examined 999999.0 | Aggregate,Gather,Aggregate,Hash Join | buf hit 16681 read 323083 | exec 548.337 ms |
| RET | ids_completed_before | 640.13 | 645.36 | 1000 | Index Scan[status] ret 772168.0x1 examined 1000000.0 | Sort (disk) | buf hit 0 read 116456 | exec 771.157 ms |

Write statements (rolled back, run1/run2 ms): insert 0.60/0.54, upd_complete 0.65/0.52, delete_by_id 0.21/0.20

## S5

445 MB 466264064 144 MB 150716416; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| HOT | keys_strict | 14646.68 (slow(>2s)) | 16710.48 | 10 | Index Scan[status] ret 99980.0x2 examined 199980.0; Seq Scan[outbox_record] ret 100000.0x2 examined 500000.0 | Sort,Aggregate,Sort,Aggregate,Hash Join | buf hit 16243 read 63290 | exec 4660.189 ms |
| MON | oldest_due_strict | 5965.04 (slow(>2s)) | 4932.87 | 1 | Index Scan[status] ret 99980.0x2 examined 199980.0; Seq Scan[outbox_record] ret 100000.0x2 examined 500000.0 | Aggregate,Gather,Aggregate,Hash Join | buf hit 16315 read 63228 | exec 4749.278 ms |
| RET | find_completed_before | 488.15 | 484.32 | 1000 | Index Scan[status] ret 81684.67x3 examined 299999.01 | Sort (disk) | buf hit 207 read 34933 | exec 361.942 ms |
| RET | ids_completed_before | 178.38 | 179.71 | 1000 | Index Scan[status] ret 245054.0x1 examined 300000.0 | Sort (disk) | buf hit 2 read 34929 | exec 217.847 ms |
| HOT | pages | 142.92 | 190.02 | 20000 | Index Scan[status] ret 10000.0x2 examined 199980.0 | Sort (disk) | buf hit 145 read 22459 | exec 97.749 ms |

Write statements (rolled back, run1/run2 ms): insert 0.32/0.39, upd_complete 0.33/0.41, delete_by_id 0.14/0.16

## S6

945 MB 990412800 292 MB 306126848; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 1230.79 | 1227.32 | 1000 | Index Scan[status] ret 205592.67x3 examined 800000.01 | Sort (disk) | buf hit 577 read 93580 | exec 859.752 ms |
| RET | ids_completed_before | 467.14 | 466.46 | 1000 | Index Scan[status] ret 616778.0x1 examined 800000.0 | Sort (disk) | buf hit 1 read 93575 | exec 570.925 ms |
| HOT | keys_strict | 411.35 | 520.27 | 10 | Seq Scan[outbox_record] ret 66666.67x3 examined 1000001.01; Index Scan[status] ret 1666.67x3 examined 200000.01 | Sort,Aggregate,Gather,Hash Join | buf hit 16116 read 134999 | exec 185.058 ms |
| MON | oldest_due_strict | 165.96 | 164.73 | 1 | Seq Scan[outbox_record] ret 66666.67x3 examined 1000001.01; Index Scan[status] ret 1666.67x3 examined 200000.01 | Aggregate,Gather,Aggregate,Hash Join | buf hit 16317 read 134808 | exec 178.744 ms |
| HOT | keys_nonstrict | 108.45 | 108.29 | 10 | Index Scan[status] ret 5000.0x1 examined 200000.0 | Sort,Aggregate | buf hit 0 read 30146 | exec 109.45 ms |

Write statements (rolled back, run1/run2 ms): insert 0.37/0.30, upd_complete 0.38/0.34, delete_by_id 0.16/0.17

## S7

973 MB 1020420096 291 MB 305307648; autovacuum during bench: last_autovacuum=none, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | delete_by_status_failed | 16559.81 (slow(>2s)) | 16196.90 | 390000 | Seq Scan[outbox_record] ret 390000.0x1 examined 1000000.0 | buf hit 390000 read 124525 | exec 12542.56 ms |
| RET | find_completed_before | 1023.34 | 1017.21 | 1000 | Seq Scan[outbox_record] ret 189714.67x3 examined 1000001.01 | Sort (disk) | buf hit 16084 read 108517 | exec 732.988 ms |
| RET | find_failed_before | 676.68 | 674.67 | 1000 | Seq Scan[outbox_record] ret 87539.0x3 examined 999999.0 | Sort (disk) | buf hit 16053 read 108548 | exec 428.68 ms |
| RET | ids_completed_before | 314.61 | 311.82 | 1000 | Seq Scan[outbox_record] ret 569144.0x1 examined 1000000.0 | Sort (disk) | buf hit 16290 read 108235 | exec 408.113 ms |
| RET | ids_failed_before | 270.64 | 266.46 | 1000 | Seq Scan[outbox_record] ret 262617.0x1 examined 1000000.0 | Sort (disk) | buf hit 16290 read 108235 | exec 306.53 ms |
| HOT | keys_strict | 56.19 | 55.36 | 10 | Bitmap Heap Scan[outbox_record] ret 3333.33x3 examined 9999.99; Bitmap Index Scan[partition_status_retry] ret 10000.0x1 examined 10000.0; Index Scan[record_key_completed_created] ret 0.0x10000 examined 10000 | Sort,Aggregate,Sort,Nested Loop | buf hit 57463 read 0 | exec 35.072 ms |

Write statements (rolled back, run1/run2 ms): insert 0.31/0.36, upd_complete 0.38/0.36, delete_by_id 0.17/0.16

## S9

1591 MB 1668145152 513 MB 537747456; autovacuum during bench: last_autovacuum=2026-11-01 03:49:15.089677+00, vacuum_count=1

| group | query | run1 | run2 | rows | plan shape |
|---|---|---:|---:|---:|---|
| RET | find_completed_before | 774.59 | 769.89 | 1000 | Seq Scan[outbox_record] ret 90237.0x3 examined 853239.0 | Sort (disk) | buf hit 15590 read 188056 | exec 451.52 ms |
| RET | ids_completed_before | 440.87 | 436.30 | 1000 | Seq Scan[outbox_record] ret 270711.0x1 examined 853240.0 | Sort (disk) | buf hit 15907 read 187663 | exec 312.306 ms |
| RET | delete_by_status_failed | 141.90 | 317.96 | 22827 | Index Scan[status] ret 22827.0x1 examined 22827.0 | buf hit 22835 read 21628 | exec 142.932 ms |
| MON | count_completed | 27.24 | 26.80 | 1 | Index Only Scan[status] ret 276404.33x3 examined 829212.99, heapfetch 3708 | Aggregate,Gather,Aggregate | buf hit 1296 read 0 | exec 39.226 ms |
| HOT | keys_strict | 6.04 | 5.88 | 10 | Index Scan[status] ret 1000.0x1 examined 1200.0; Index Scan[record_key_completed_created] ret 0.05x1000 examined 1050.0 | Sort,Aggregate,Sort,Nested Loop | buf hit 5164 read 0 | exec 7.15 ms |

Write statements (rolled back, run1/run2 ms): insert 0.40/0.35, upd_complete 0.35/0.34, delete_by_id 0.13/0.15

Churn (S9, 500k inserts, no manual VACUUM after): ; before 907 MB 950591488 295 MB 309043200; after 1591 MB 1668145152 513 MB 537747456; churn_seconds: 562; pg_stat after (live, dead, last_vacuum, last_autovacuum, last_autoanalyze, vac, autovac): 818244 | 58020 | 2026-11-01 03:40:22.459164+00 | 2026-11-01 03:49:15.089677+00 | 2026-11-01 03:47:46.915619+00 | 1 | 4

## Repository level (run 1; median ms, slowest 4 methods)

- S1 jdbc: find_completed_before 17.72, delete_completed_batch 7.76, insert 2.41, keys_strict 2.35
- S1 jpa: find_completed_before 11.74, delete_completed_batch 8.97, keys_strict 4.36, delete_by_id 3.02
- S2 jdbc: find_completed_before 1831.34, delete_completed_batch 658.46, count_completed 18.77, keys_strict 11.92
- S2 jpa: find_completed_before 268.58, delete_completed_batch 228.10, count_completed 105.59, count_failed 22.46
- S4 jdbc: keys_strict 4054.27, find_completed_before 1548.14, keys_nonstrict 1357.49, delete_completed_batch 653.31
- S4 jpa: keys_strict 1785.71, keys_nonstrict 1078.46, oldest_due_strict 589.78, find_completed_before 275.22
- S5 jdbc: keys_strict 14594.17, oldest_due_strict 5869.09, find_completed_before 491.30, delete_completed_batch 191.64
- S5 jpa: oldest_due_strict 5968.13, keys_strict 5735.01, pages 209.89, find_completed_before 125.09
