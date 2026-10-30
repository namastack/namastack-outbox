-- H09 non-partial: the H10 per-key index (replaces record_key_created); no partial index.
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_status_created
    ON outbox_record (record_key, status, created_at, id);
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
