-- H31 variant a' (add only): keep idx_outbox_record_status, add (status, created_at)
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created ON outbox_record (status, created_at);
