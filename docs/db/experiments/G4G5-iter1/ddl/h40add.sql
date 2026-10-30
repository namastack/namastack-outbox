CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);
