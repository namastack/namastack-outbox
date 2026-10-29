-- Avoid a bitmap intersection with every NEW record during a bulk key lookup.
-- Retain record_key as the leading column for key-only operations.
CREATE INDEX IF NOT EXISTS idx_outbox_record_key_status_created
    ON outbox_record (record_key, status, created_at);
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
