-- U2b (portable): same index with id appended (fully ordered probes, no incremental sort); replaces rkcc and rkc.
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created;
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
