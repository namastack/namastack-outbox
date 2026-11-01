# Equivalence main vs v1 (postgres, S3)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=0000d206-4940-4379-a1c6-9f1da89e0afb,COMPLETED,order-000000000000000000000000000766,io.namastack.outbox.bench.OrderShippedPayload,{"d": |
| upd_retry | PASS | updated=1 post=0000d206-4940-4379-a1c6-9f1da89e0afb,NEW,order-000000000000000000000000000766,io.namastack.outbox.bench.OrderShippedPayload,{"d":"8fAyr |
| upd_fail | PASS | updated=1 post=0000d206-4940-4379-a1c6-9f1da89e0afb,FAILED,order-000000000000000000000000000766,io.namastack.outbox.bench.OrderShippedPayload,{"d":"8f |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [3500] |
| count_failed | PASS | 1 row(s) = [1500] |
| count_completed | PASS | 1 row(s) = [0] |
| count_statuses | PASS | a={NEW=3500, FAILED=1500} b={FAILED=1500, NEW=3500} |
| count_statuses_all | PASS | a={NEW=3500, FAILED=1500, COMPLETED=0} b={FAILED=1500, NEW=3500, COMPLETED=0} |
| count_partitions | PASS | 1 row(s) = [3500] |
| count_partition_single | PASS | 1 row(s) = [11] |
| oldest_created | PASS | 1 row(s) = [2025-12-25 13:29:47.551541+00] |
| oldest_due_strict | PASS | 1 row(s) = [2025-12-25 13:29:47.551541+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2025-12-25 13:29:47.551541+00] |
| find_completed_before | PASS | 0 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 0 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | ERROR | ERROR: syntax error at or near ")" |
| delete_by_status_failed | PASS | updated=1500 post=NEW,3500 |
| delete_by_key_status | PASS | updated=1 post=FAILED,1500/NEW,3499 |
| find_pending_all | PASS | 3500 row(s) |
| find_failed_all | PASS | 1500 row(s) |
| find_incomplete_by_key | PASS | 1 row(s) = [5f26dd40-6035-4bae-bd0d-d626b8a7f1d1, NEW] |
