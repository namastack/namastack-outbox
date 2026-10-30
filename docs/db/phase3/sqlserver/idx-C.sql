-- C: non-filtered completed_at-leading covering (MySQL/MariaDB winner) + drop prefix-duplicate
CREATE INDEX idx_outbox_record_incomplete_created ON outbox_record (completed_at, created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key);
DROP INDEX idx_outbox_record_record_key_created ON outbox_record;
UPDATE STATISTICS outbox_record WITH FULLSCAN;
