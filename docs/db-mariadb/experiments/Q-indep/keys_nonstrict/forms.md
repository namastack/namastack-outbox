# keys_nonstrict: proposed forms (independent proposer)

## ne-split
- **Idea:** Two NOT EXISTS (older created_at / same created_at smaller id) restricted to due NEW incomplete rows of the same key.
- **Why it may be faster:** Removes the OR from the inner range. Inner conditions status/next_retry_at are not in B-idx2, so the probe reads (record_key, completed_at IS NULL) range and filters after a PK lookup unless idx1 is used for the inner.
- **Access path needed:** Outer idx_outbox_record_status_completed_created_id; inner idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at) range then PK lookup for status/next_retry_at; alternatively idx_outbox_record_status_completed_created_id with status/completed_at prefix and record_key filter (poor).
- **Semantic risks:** Same rows/order as base. Inner needs PK lookups to evaluate status and next_retry_at (a gap that the strict variant does not have).
- **MySQL portability:** Valid MySQL 8.

## ne-tuple
- **Idea:** Row-constructor comparison for the predecessor test.
- **Why it may be faster:** Compact expression of the (created_at, id) order.
- **Access path needed:** idx_outbox_record_key_completed_created_id for inner, idx_outbox_record_status_completed_created_id for outer.
- **Semantic risks:** May not give a composite range on MariaDB; identical results.
- **MySQL portability:** Valid MySQL 8.

## lj-antijoin
- **Idea:** LEFT JOIN anti-join (STRAIGHT_JOIN keeps o as the driving table).
- **Why it may be faster:** Not-exists join optimization; join with the status/due conditions in ON allows the inner to be matched in the first-match fashion.
- **Access path needed:** Outer idx_outbox_record_status_completed_created_id; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** Same rows. The conditions on `earlier` MUST stay in ON (not WHERE), otherwise the LEFT JOIN degenerates; they are in ON here.
- **MySQL portability:** Valid MySQL 8.

## win-head
- **Idea:** Rank the due NEW incomplete rows per key; the first due record of each key is rn = 1. The status/due/incomplete filters are inside the window input.
- **Why it may be faster:** A single scan of the due NEW rows ({status, completed_at IS NULL} prefix of idx1 plus a next_retry_at filter) replaces a probe per row. The partition filter is applied outside; the optimizer cannot push it below the window.
- **Access path needed:** idx_outbox_record_status_completed_created_id (status, completed_at IS NULL prefix, created_at, id order delivers rows already sorted by created_at, id; partition_no, next_retry_at, record_key covered, index-only). Window needs record_key partitioning, so a filesort/temp table follows.
- **Semantic risks:** Same rows/order as base. Materializes all due NEW rows of all partitions, not only owned ones (poor under partial ownership S8/S16 and S4 backlog).
- **MySQL portability:** Valid MySQL 8.

## group-min
- **Idea:** GROUP BY record_key with MIN(created_at) ordering (main's shape) plus completed_at IS NULL.
- **Why it may be faster:** No per-row probe at all; one scan of the due NEW rows.
- **Access path needed:** idx_outbox_record_status_completed_created_id index-only scan (record_key, partition_no, next_retry_at, created_at are all in it); needs temp table / filesort for GROUP BY since record_key is last in the index.
- **Semantic risks:** SEMANTIC DIFFERENCE: key set is identical, but the order among keys whose first due record has the same created_at may differ (id of the head vs MIN(id)), so with LIMIT the keys at a tie boundary can differ. Also the full group is materialized (cost grows with the due backlog, bad at S4). Keep only as comparison.
- **MySQL portability:** Valid MySQL 8 (ONLY_FULL_GROUP_BY fine: ORDER BY uses aggregates).

## ne-split-force-idx1
- **Idea:** ne-split with FORCE INDEX on the outer table.
- **Why it may be faster:** Pins the ordered scan with early stop.
- **Access path needed:** idx_outbox_record_status_completed_created_id.
- **Semantic risks:** HIGH RISK: fails if idx_outbox_record_status_completed_created_id does not exist.
- **MySQL portability:** Valid syntax; index must exist.
