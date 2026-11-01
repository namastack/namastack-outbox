# Equivalence main vs v1 (postgres, S2)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=e28f0a26-02bd-4a13-b479-aaa56dc84814,COMPLETED,order-000000000000000000000000000289,io.namastack.outbox.bench.CustomerUpdatedPayload,{" |
| upd_retry | PASS | updated=1 post=e28f0a26-02bd-4a13-b479-aaa56dc84814,NEW,order-000000000000000000000000000289,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d":"YL |
| upd_fail | PASS | updated=1 post=e28f0a26-02bd-4a13-b479-aaa56dc84814,FAILED,order-000000000000000000000000000289,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d": |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [1200] |
| count_failed | PASS | 1 row(s) = [12800] |
| count_completed | PASS | 1 row(s) = [986000] |
| count_statuses | PASS | a={NEW=1200, FAILED=12800} b={FAILED=12800, NEW=1200} |
| count_statuses_all | PASS | a={NEW=1200, FAILED=12800, COMPLETED=986000} b={COMPLETED=986000, FAILED=12800, NEW=1200} |
| count_partitions | PASS | 1 row(s) = [278] |
| count_partition_single | PASS | 1 row(s) = [10] |
| oldest_created | PASS | 1 row(s) = [2025-12-23 16:18:24.689947+00] |
| oldest_due_strict | PASS | 1 row(s) = [2025-12-23 16:18:24.689947+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2025-12-23 16:18:24.689947+00] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,985000/FAILED,12800/NEW,1200 |
| delete_by_status_failed | PASS | updated=12800 post=COMPLETED,986000/NEW,1200 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,986000/FAILED,12800/NEW,1199 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1 row(s) = [e28f0a26-02bd-4a13-b479-aaa56dc84814, NEW] |
