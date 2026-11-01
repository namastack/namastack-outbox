# Equivalence main vs v1 (postgres, S7)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=39aee71e-16d5-4fe4-a1ca-196c7ba43574,COMPLETED,order-000000000000000000000000005601,io.namastack.outbox.bench.OrderShippedPayload,{"d": |
| upd_retry | PASS | updated=1 post=39aee71e-16d5-4fe4-a1ca-196c7ba43574,NEW,order-000000000000000000000000005601,io.namastack.outbox.bench.OrderShippedPayload,{"d":"vJzQW |
| upd_fail | PASS | updated=1 post=39aee71e-16d5-4fe4-a1ca-196c7ba43574,FAILED,order-000000000000000000000000005601,io.namastack.outbox.bench.OrderShippedPayload,{"d":"vJ |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [10000] |
| count_failed | PASS | 1 row(s) = [390000] |
| count_completed | PASS | 1 row(s) = [600000] |
| count_statuses | PASS | a={NEW=10000, FAILED=390000} b={FAILED=390000, NEW=10000} |
| count_statuses_all | PASS | a={NEW=10000, FAILED=390000, COMPLETED=600000} b={COMPLETED=600000, FAILED=390000, NEW=10000} |
| count_partitions | PASS | 1 row(s) = [10000] |
| count_partition_single | PASS | 1 row(s) = [33] |
| oldest_created | PASS | 1 row(s) = [2025-10-05 18:16:04.079235+00] |
| oldest_due_strict | PASS | 1 row(s) = [2025-10-05 18:16:04.079235+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2025-10-05 18:16:04.079235+00] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 1000 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 1000 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,599000/FAILED,390000/NEW,10000 |
| delete_by_status_failed | PASS | updated=390000 post=COMPLETED,600000/NEW,10000 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,600000/FAILED,390000/NEW,9999 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1 row(s) = [39aee71e-16d5-4fe4-a1ca-196c7ba43574, NEW] |
