# Equivalence my-head vs my-ne (mysql, S5)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 20000 vs 20000 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=019b796d-d600-7001-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=019b780e-7cb0-74ee-99a9-de964da3a3ae,COMPLETED,order-000000000000000000000000000086,io.namastack.outbox.bench.OrderShippedPayload,{"d": |
| upd_retry | PASS | updated=1 post=019b780e-7cb0-74ee-99a9-de964da3a3ae,NEW,order-000000000000000000000000000086,io.namastack.outbox.bench.OrderShippedPayload,{"d":"JAGnm |
| upd_fail | PASS | updated=1 post=019b780e-7cb0-74ee-99a9-de964da3a3ae,FAILED,order-000000000000000000000000000086,io.namastack.outbox.bench.OrderShippedPayload,{"d":"JA |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [199980] |
| count_failed | PASS | 1 row(s) = [20] |
| count_completed | PASS | 1 row(s) = [300000] |
| count_statuses | PASS | a={FAILED=20, NEW=199980} b={FAILED=20, NEW=199980} |
| count_statuses_all | PASS | a={COMPLETED=300000, FAILED=20, NEW=199980} b={COMPLETED=300000, FAILED=20, NEW=199980} |
| count_partitions | PASS | 1 row(s) = [199980] |
| count_partition_single | PASS | 1 row(s) = [1999] |
| oldest_created | PASS | 1 row(s) = [2026-01-01 05:36:14] |
| oldest_due_strict | PASS | 1 row(s) = [2026-01-01 05:36:14] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2026-01-01 05:36:14] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,299000/FAILED,20/NEW,199980 |
| delete_by_status_failed | PASS | updated=20 post=COMPLETED,300000/NEW,199980 |
| delete_by_key_status | PASS | updated=1999 post=COMPLETED,300000/FAILED,20/NEW,197981 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1999 row(s) |
