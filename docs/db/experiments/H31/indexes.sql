-- H31 variant a (replace): idx_outbox_record_status (status) -> (status, created_at)
DROP INDEX IF EXISTS idx_outbox_record_status;
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created ON outbox_record (status, created_at);
