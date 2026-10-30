CREATE INDEX idx_outbox_record_completed_created ON outbox_record (completed_at, created_at, id);
