-- C: completed_at-leading non-partial outer index (covering), drop prefix-duplicate record_key_created
ALTER TABLE outbox_record ADD INDEX idx_outbox_record_incomplete_created (completed_at, created_at, id, partition_no, status, next_retry_at, record_key), DROP INDEX idx_outbox_record_record_key_created;
ANALYZE TABLE outbox_record;
