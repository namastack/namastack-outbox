# Equivalence main vs v1 (postgres, S6)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=82e96087-a184-447b-bfb8-dfdcf7b88787,COMPLETED,order-000000000000000000000000002019,io.namastack.outbox.bench.CustomerUpdatedPayload,{" |
| upd_retry | PASS | updated=1 post=82e96087-a184-447b-bfb8-dfdcf7b88787,NEW,order-000000000000000000000000002019,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d":"jq |
| upd_fail | PASS | updated=1 post=82e96087-a184-447b-bfb8-dfdcf7b88787,FAILED,order-000000000000000000000000002019,io.namastack.outbox.bench.CustomerUpdatedPayload,{"d": |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [200000] |
| count_failed | PASS | 1 row(s) = [0] |
| count_completed | PASS | 1 row(s) = [800000] |
| count_statuses | PASS | a={NEW=200000, FAILED=0} b={NEW=200000, FAILED=0} |
| count_statuses_all | PASS | a={NEW=200000, FAILED=0, COMPLETED=800000} b={COMPLETED=800000, NEW=200000, FAILED=0} |
| count_partitions | PASS | 1 row(s) = [200000] |
| count_partition_single | PASS | 1 row(s) = [847] |
| oldest_created | PASS | 1 row(s) = [2025-12-02 19:28:49.830541+00] |
| oldest_due_strict | PASS | 1 row(s) = [2025-12-03 09:40:48.856006+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2025-12-03 09:40:48.856006+00] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,799000/NEW,200000 |
| delete_by_status_failed | PASS | updated=0 post=COMPLETED,800000/NEW,200000 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,800000/NEW,199999 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1 row(s) = [82e96087-a184-447b-bfb8-dfdcf7b88787, NEW] |
