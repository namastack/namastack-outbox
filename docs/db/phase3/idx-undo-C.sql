ALTER TABLE outbox_record DROP INDEX idx_outbox_record_incomplete_created, ADD INDEX idx_outbox_record_record_key_created (record_key, created_at);
