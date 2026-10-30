CREATE INDEX idx_outbox_record_completed_partial ON outbox_record (completed_at) WHERE status = 'COMPLETED';
