# count_statuses: proposed forms (independent proposer)

## use-idx4
- **Idea:** Hint the narrowest covering index (status, completed_at, partition_no).
- **Why it may be faster:** Smaller secondary index (no id/record_key) means fewer pages per count; also guarantees an index-order GROUP BY without temp table.
- **Access path needed:** idx_outbox_record_status_completed_partition.
- **Semantic risks:** HIGH RISK: USE INDEX with an unknown index name errors (1176) on schemas lacking it.
- **MySQL portability:** Valid syntax; index must exist.

## split-null
- **Idea:** Split into the incomplete and the completed halves of the index (both together cover all rows).
- **Why it may be faster:** Each half is a tight (status, completed_at) range; the NOT NULL half for NEW/FAILED is empty, answered by one index probe. Gives the optimizer an explicit index-order group scan for both. No benefit for the COMPLETED count itself.
- **Access path needed:** idx_outbox_record_status_completed_partition or idx_outbox_record_status_completed_retry / idx_outbox_record_status_completed_created_id prefix (status, completed_at).
- **Semantic risks:** Same counts. `completed_at IS NOT NULL` here is used only to partition rows (union is complete), not as a filter that drops COMPLETED. SUM returns DECIMAL, not BIGINT (harness/driver reading via getLong is fine, but flag it).
- **MySQL portability:** Valid MySQL 8.

## values-subquery (needs code change)
- **Idea:** Driving status list; one correlated COUNT(*) per status.
- **Why it may be faster:** One clean ref count per status (no GROUP BY machinery); also returns zero rows for absent statuses.
- **Access path needed:** Any of idx_outbox_record_status_completed_created_id/idx_outbox_record_status_completed_retry/idx_outbox_record_status_completed_partition (status prefix); optimizer picks the smallest.
- **Semantic risks:** NEEDS CODE CHANGE: :statusesRows expands to `(?),(?)`. Result includes a zero row for absent statuses (the repository already zero-fills, so the map is equal).
- **MySQL portability:** MySQL needs ROW() in VALUES.

## union-per-status (needs code change)
- **Idea:** One scalar COUNT per status via UNION ALL (shown for two statuses).
- **Why it may be faster:** Each branch is a plain ref count with no grouping; branches can use different narrowest indexes.
- **Access path needed:** idx_outbox_record_status_completed_partition/idx_outbox_record_status_completed_retry.
- **Semantic risks:** NEEDS CODE CHANGE: statement generated per status with :status1..N; the label column repeats the bound status value. Zero rows are returned for absent statuses.
- **MySQL portability:** Valid MySQL 8.
