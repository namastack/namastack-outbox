ALTER TABLE outbox_record ADD INDEX idx_outbox_record_status_completed (status, completed_at);
