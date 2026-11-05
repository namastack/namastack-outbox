# count_partitions: proposed forms (independent proposer)

## split-null
- **Idea:** Manual skip scan: split the free middle column `completed_at` into its NULL and NOT NULL halves so partition_no becomes the next key part in both.
- **Why it may be faster:** With (status, completed_at, partition_no) the equality prefix covers status and completed_at, so partition_no IN (...) becomes a multi-range seek (one dive per owned partition) instead of a scan of every NEW row of the status. Wins when the owned partition set is a fraction (S8 64/256, S16 16/256).
- **Access path needed:** idx_outbox_record_status_completed_partition (status, completed_at, partition_no) covering; idx_outbox_record_status_completed_created_id/idx_outbox_record_status_completed_retry also serve it.
- **Semantic risks:** Same count; the NOT NULL half is a range over completed rows of that status (empty for NEW/FAILED, large for COMPLETED, where it equals base work). The predicate is partitioning rows, not filtering them, so COMPLETED counts stay correct. CAST needed to return BIGINT.
- **MySQL portability:** Valid MySQL 8.

## use-idx4
- **Idea:** Hint the narrowest covering index.
- **Why it may be faster:** Fewer pages than the wide idx1; index-only.
- **Access path needed:** idx_outbox_record_status_completed_partition.
- **Semantic risks:** HIGH RISK: fails on schemas lacking the index name.
- **MySQL portability:** Valid syntax; index must exist.

## split-null-idx4
- **Idea:** split-null with the narrow index pinned.
- **Why it may be faster:** Combines the multi-range partition seeks with the smallest index.
- **Access path needed:** idx_outbox_record_status_completed_partition.
- **Semantic risks:** HIGH RISK: index hint.
- **MySQL portability:** Valid syntax; index must exist.
