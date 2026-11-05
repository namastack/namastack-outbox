# Equivalence cmp-main vs cmp-jdbc-opt (mariadb, S1)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=012ecd3b-e8bd-4ca0-80fb-bb36865cbec1,COMPLETED,order-000000000000000000000000000041,io.namastack.outbox.bench.OrderShippedPayload,{"d": |
| upd_retry | PASS | updated=1 post=012ecd3b-e8bd-4ca0-80fb-bb36865cbec1,NEW,order-000000000000000000000000000041,io.namastack.outbox.bench.OrderShippedPayload,{"d":"eBv6o |
| upd_fail | PASS | updated=1 post=012ecd3b-e8bd-4ca0-80fb-bb36865cbec1,FAILED,order-000000000000000000000000000041,io.namastack.outbox.bench.OrderShippedPayload,{"d":"eB |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [180] |
| count_failed | PASS | 1 row(s) = [20] |
| count_completed | PASS | 1 row(s) = [9800] |
| count_statuses | PASS | a={NEW=180, FAILED=20} b={FAILED=20, NEW=180} |
| count_statuses_all | PASS | a={NEW=180, FAILED=20, COMPLETED=9800} b={COMPLETED=9800, FAILED=20, NEW=180} |
| count_partitions | PASS | 1 row(s) = [180] |
| count_partition_single | PASS | 1 row(s) = [1] |
| oldest_created | PASS | 1 row(s) = [2026-01-01 06:33:20] |
| oldest_due_strict | PASS | 1 row(s) = [2026-01-01 06:33:20] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2026-01-01 06:33:20] |
| find_completed_before | PASS | same id set, order differs inside ties |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | same id set, order differs inside ties |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,8800/FAILED,20/NEW,180 |
| delete_by_status_failed | PASS | updated=20 post=COMPLETED,9800/NEW,180 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,9800/FAILED,20/NEW,179 |
| find_pending_all | PASS | 180 row(s) |
| find_failed_all | PASS | 20 row(s) |
| find_incomplete_by_key | PASS | 1 row(s) = [97dd2c0e-36c5-41ff-a841-eff27517670b, NEW] |
