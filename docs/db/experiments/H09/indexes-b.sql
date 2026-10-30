-- H09b: H04 indexes with the status-based incomplete predicate  WHERE status <> 'COMPLETED'
DROP INDEX IF EXISTS idx_outbox_record_incomplete_key;
DROP INDEX IF EXISTS idx_outbox_record_incomplete_created;
CREATE INDEX idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE status <> 'COMPLETED';
CREATE INDEX idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE status <> 'COMPLETED';
