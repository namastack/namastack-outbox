DROP INDEX IF EXISTS idx_outbox_record_status; CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at);
