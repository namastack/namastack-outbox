# Equivalence A2 vs v1 (postgres, S2)

| query | verdict | detail |
|---|---|---|
| keys_strict | PASS | 10 keys, same order |
| keys_nonstrict | PASS | 10 keys, same order |
| pages | PASS | 10 vs 10 rows over 10 keys; per-key id order equal |
| insert | PASS | updated=1 post=74ad8585-ddc5-3e4c-ac45-40be478daeb2,NEW,order-bench-00000000000000000001,io.namastack.outbox.bench.OrderShippedPayload,{"d":"xxxxxxxxx |
| upd_complete | PASS | updated=1 post=0060306a-c6e3-46f8-8e68-f2a5979c63f3,COMPLETED,order-000000000000000000000000000893,io.namastack.outbox.bench.InvoiceIssuedPayload,{"d" |
| upd_retry | PASS | updated=1 post=0060306a-c6e3-46f8-8e68-f2a5979c63f3,NEW,order-000000000000000000000000000893,io.namastack.outbox.bench.InvoiceIssuedPayload,{"d":"hZFW |
| upd_fail | PASS | updated=1 post=0060306a-c6e3-46f8-8e68-f2a5979c63f3,FAILED,order-000000000000000000000000000893,io.namastack.outbox.bench.InvoiceIssuedPayload,{"d":"h |
| delete_by_id | PASS | updated=1 post= |
| count_new | PASS | 1 row(s) = [1200] |
| count_failed | PASS | 1 row(s) = [12800] |
| count_completed | PASS | 1 row(s) = [986000] |
| count_statuses | PASS | a={FAILED=12800, NEW=1200} b={FAILED=12800, NEW=1200} |
| count_statuses_all | PASS | a={COMPLETED=986000, FAILED=12800, NEW=1200} b={COMPLETED=986000, FAILED=12800, NEW=1200} |
| count_partitions | PASS | 1 row(s) = [1200] |
| count_partition_single | PASS | 1 row(s) = [3] |
| oldest_created | PASS | 1 row(s) = [2025-12-07 15:24:32.880111+00] |
| oldest_due_strict | PASS | 1 row(s) = [2025-12-07 15:24:32.880111+00] |
| oldest_due_nonstrict | PASS | 1 row(s) = [2025-12-07 15:24:32.880111+00] |
| find_completed_before | PASS | 1000 rows, same ids same order |
| find_failed_before | PASS | 0 rows, same ids same order |
| ids_completed_before | PASS | 1000 rows, same ids same order |
| ids_failed_before | PASS | 0 rows, same ids same order |
| delete_by_ids | PASS | updated=1000 post=COMPLETED,985000/FAILED,12800/NEW,1200 |
| delete_by_status_failed | PASS | updated=12800 post=COMPLETED,986000/NEW,1200 |
| delete_by_key_status | PASS | updated=1 post=COMPLETED,986000/FAILED,12800/NEW,1199 |
| find_pending_all | SKIP | unbounded |
| find_failed_all | SKIP | unbounded |
| find_incomplete_by_key | PASS | 1 row(s) = [522fbaa2-b367-4c8a-b8c2-8a3ff3228784, NEW] |
