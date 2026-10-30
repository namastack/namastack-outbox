DROP INDEX IF EXISTS idx_outbox_record_status; DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry; CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at);
