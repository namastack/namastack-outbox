-- H03b (secondary measurement): non-partial covering variant, works with the UNCHANGED queries
-- (no `o.completed_at IS NULL` needed) and for any status / generic plans. Replaces
-- idx_outbox_record_partition_status_retry (same key columns + INCLUDE), so drop that one.
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
CREATE INDEX IF NOT EXISTS idx_outbox_record_partition_status_retry
    ON outbox_record (partition_no, status, next_retry_at)
    INCLUDE (record_key, created_at, id);
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
