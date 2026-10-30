-- U1 (PG): one status-based partial per-key index for G1 head probe and G2; drops the prefix-duplicate record_key_created.
CREATE INDEX IF NOT EXISTS idx_outbox_record_open_key_created
    ON outbox_record (record_key, created_at, id) WHERE status IN ('NEW', 'FAILED');
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
