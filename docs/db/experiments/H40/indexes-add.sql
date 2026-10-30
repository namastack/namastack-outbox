-- H40 variant add: (status, completed_at) next to the existing indexes (the index retention.md recommends today)
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_completed ON outbox_record (status, completed_at);
