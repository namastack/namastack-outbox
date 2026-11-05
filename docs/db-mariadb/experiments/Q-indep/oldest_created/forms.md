# oldest_created: proposed forms (independent proposer)

## ord-limit
- **Idea:** ORDER BY created_at LIMIT :limit (mandatory comparison; equals the branch SQL).
- **Why it may be faster:** Walks idx1 in (created_at, id) order after (status, completed_at IS NULL) and stops at the first row whose partition_no is owned; cheap when owned partitions are dense, linear in the skipped rows otherwise. Returns zero rows when empty.
- **Access path needed:** idx_outbox_record_status_completed_created_id (status, completed_at, created_at ...) with partition_no filtered in-index.
- **Semantic risks:** Cost depends on how many leading rows belong to non-owned partitions (S8/S16 matter).
- **MySQL portability:** Valid MySQL 8.

## min-agg
- **Idea:** MIN(created_at) with completed_at IS NULL (mandatory comparison).
- **Why it may be faster:** With a partition_no IN filter the MIN/MAX range optimization (first/last key of the index) does not apply because partition_no is not an equality prefix before created_at; the plan scans all incomplete rows of the status through the in-index filter. Expected to lose to ord-limit, hence the comparison.
- **Access path needed:** idx_outbox_record_status_completed_created_id (index-only; full range scan of the prefix).
- **Semantic risks:** Returns one row with NULL when empty rather than zero rows; the repository must treat NULL as no value. Otherwise equal.
- **MySQL portability:** Valid MySQL 8.

## min-over-limited
- **Idea:** Aggregate over an ordered limited derived table.
- **Why it may be faster:** Forces the early-stopping ordered scan inside the derived table and returns a single row; useful if MIN alone would pick a scan.
- **Access path needed:** idx_outbox_record_status_completed_created_id.
- **Semantic risks:** Equal result when :limit = 1 (and for any limit >= 1 the minimum); one NULL row when empty. Derived table materialization overhead is negligible for one row.
- **MySQL portability:** Valid MySQL 8.

## ord-limit-force-idx1
- **Idea:** ord-limit with the ordered index pinned.
- **Why it may be faster:** Prevents the optimizer from preferring a partition-selective index with a filesort.
- **Access path needed:** idx_outbox_record_status_completed_created_id.
- **Semantic risks:** HIGH RISK: fails on schemas lacking the index (e.g. main's DDL).
- **MySQL portability:** Valid syntax; index must exist.

## ord-limit-id
- **Idea:** ORDER BY created_at, id so the sort exactly matches the index key order (created_at, id).
- **Why it may be faster:** Removes any doubt that the index order satisfies the sort (index continues with id); same plan, deterministic when :limit > 1.
- **Access path needed:** idx_outbox_record_status_completed_created_id.
- **Semantic risks:** Equal result for the values returned (ties on created_at yield equal values).
- **MySQL portability:** Valid MySQL 8.
