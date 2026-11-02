# Equivalence E1P vs v1 (postgres, S4)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 100 keys, same order |
| keys_nonstrict | PASS | 100 keys, same order |
| pages | PASS | 100 vs 100 rows over 100 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=000006cc-0c6c-4771-97b4-494fcf6fb4b5,COMPLETED,order-000000000000000000000000788874,io.namastack.outbox.bench.CustomerUpdatedPayload,{" |
| upd_retry | PASS | updated=1 post=000006cc-0c6c-4771-97b4-494fcf6fb4b5,NEW,order-000000000000000000000000788874,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d":"6T |
| upd_fail | PASS | updated=1 post=000006cc-0c6c-4771-97b4-494fcf6fb4b5,FAILED,order-000000000000000000000000788874,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d": |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [1000000] |
| count_failed | PASS | 1 row(s) = [0] |
| count_completed | PASS | 1 row(s) = [1000000] |
| count_statuses | PASS | a={NEW=1000000, FAILED=0} b={NEW=1000000, FAILED=0} |
| count_statuses_all | PASS | a={COMPLETED=1000000, NEW=1000000, FAILED=0} b={COMPLETED=1000000, NEW=1000000, FAILED=0} |
| count_partitions | PASS | 1 row(s) = [1000000] |
| count_partition_single | PASS | 1 row(s) = [3963] |
| oldest_created | PASS | 1 row(s) = [2026-01-01 11:00:00.004632+00] |
| oldest_due_strict | PASS | 1 row(s) = [2026-01-01 11:00:00.004632+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2026-01-01 11:00:00.004632+00] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,999000/NEW,1000000 |
| delete_by_status_failed | PASS | updated=0 post=COMPLETED,1000000/NEW,1000000 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,1000000/NEW,999999 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1 row(s) = [97dd2c0e-36c5-41ff-a841-eff27517670b, NEW] |
