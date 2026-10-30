-- S: status-leading outer index (covering) + per-key (record_key,status,created_at); drop record_key_created
ALTER TABLE outbox_record ADD INDEX idx_outbox_record_status_created (status, created_at, id, partition_no, next_retry_at, record_key), ADD INDEX idx_outbox_record_record_key_status_created (record_key, status, created_at), DROP INDEX idx_outbox_record_record_key_created;
ANALYZE TABLE outbox_record;
