-- H10 variant without the id tie-breaker (smaller; ties on created_at resolved by an Incremental Sort).
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_status_created
    ON outbox_record (record_key, status, created_at);
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
