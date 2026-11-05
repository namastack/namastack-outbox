# oldest_due_nonstrict: proposed forms (independent proposer)

## min-agg
- **Idea:** MIN(next_retry_at) (mandatory MIN form; equals the branch SQL).
- **Why it may be faster:** idx_outbox_record_status_completed_retry (status, completed_at IS NULL, next_retry_at <= now ...) provides next_retry_at in order, but partition_no is the trailing column, so the filter is evaluated in the index while scanning the due range: MIN cannot just read the first entry unless the first entry passes the partition filter.
- **Access path needed:** idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no), index-only.
- **Semantic risks:** NULL row when empty.
- **MySQL portability:** Valid MySQL 8.

## ord-limit
- **Idea:** ORDER BY next_retry_at LIMIT :limit (mandatory ORDER BY form).
- **Why it may be faster:** Explicit ordered scan of the due range with early stop at the first owned-partition row.
- **Access path needed:** idx_outbox_record_status_completed_retry.
- **Semantic risks:** Zero rows when empty instead of NULL; :limit must be 1 for equality with MIN.
- **MySQL portability:** Valid MySQL 8.

## min-over-limited
- **Idea:** Single-row aggregate over the early-stopping ordered derived table.
- **Why it may be faster:** Combines early stop with a MIN-style single NULL-on-empty row.
- **Access path needed:** idx_outbox_record_status_completed_retry.
- **Semantic risks:** Equal result for :limit >= 1.
- **MySQL portability:** Valid MySQL 8.

## ord-limit-force-idx3
- **Idea:** ord-limit with idx3 pinned.
- **Why it may be faster:** Avoids a plan through idx1 (also status/completed_at prefix) that does not deliver next_retry_at order.
- **Access path needed:** idx_outbox_record_status_completed_retry.
- **Semantic risks:** HIGH RISK: index hint.
- **MySQL portability:** Valid syntax; index must exist.

## min-force-idx3
- **Idea:** min-agg with idx3 pinned.
- **Why it may be faster:** Prefers the index in which next_retry_at is ordered, for the MIN scan.
- **Access path needed:** idx_outbox_record_status_completed_retry.
- **Semantic risks:** HIGH RISK: index hint.
- **MySQL portability:** Valid syntax; index must exist.
