-- H14 state 1 (portable): H10 index, replaces record_key_created.
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_status_created
    ON outbox_record (record_key, status, created_at, id);
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
