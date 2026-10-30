-- H10 (add-only): ordered, non-partial bulk-load index. Same column list for every dialect.
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_status_created
    ON outbox_record (record_key, status, created_at, id);
