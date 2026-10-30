DROP INDEX idx_outbox_rec_incomplete;
CREATE INDEX idx_outbox_rec_key_cr ON outbox_record (record_key, created_at);
