-- H04: H02 probe index + partial index over incomplete records in (created_at, id) order,
-- covering the outer filter columns so the early-stop scan can run index-only.
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
