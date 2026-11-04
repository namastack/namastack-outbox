# Equivalence UABC-L-NS vs v1 (postgres, S7)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=0007c81b-9543-42b5-b99b-acf062a17b5f,COMPLETED,order-000000000000000000000000005714,io.namastack.outbox.bench.CustomerUpdatedPayload,{" |
| upd_retry | PASS | updated=1 post=0007c81b-9543-42b5-b99b-acf062a17b5f,NEW,order-000000000000000000000000005714,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d":"QW |
| upd_fail | PASS | updated=1 post=0007c81b-9543-42b5-b99b-acf062a17b5f,FAILED,order-000000000000000000000000005714,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d": |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [10000] |
| count_failed | PASS | 1 row(s) = [390000] |
| count_completed | PASS | 1 row(s) = [600000] |
| count_statuses | PASS | a={FAILED=390000, NEW=10000} b={FAILED=390000, NEW=10000} |
| count_statuses_all | PASS | a={COMPLETED=600000, FAILED=390000, NEW=10000} b={COMPLETED=600000, FAILED=390000, NEW=10000} |
| count_partitions | PASS | 1 row(s) = [10000] |
| count_partition_single | PASS | 1 row(s) = [35] |
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
| find_incomplete_by_key | PASS | 1 row(s) = [5f26dd40-6035-4bae-bd0d-d626b8a7f1d1, NEW] |
