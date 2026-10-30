-- H16 U2b on top of H08a: record_key_completed_created_id replaces record_key_completed_created
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created;
