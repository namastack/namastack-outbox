-- H07b (covering): as H07a plus the outer filter/output columns so the scan can be index-only
-- (MySQL/MariaDB/Oracle equivalent: append them as trailing key columns; InnoDB appends id implicitly).
DROP INDEX IF EXISTS idx_outbox_record_completed_created;
CREATE INDEX IF NOT EXISTS idx_outbox_record_completed_created
    ON outbox_record (completed_at, created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key);
DROP INDEX IF EXISTS idx_outbox_record_incomplete_created;
DROP INDEX IF EXISTS idx_outbox_record_incomplete_key;
