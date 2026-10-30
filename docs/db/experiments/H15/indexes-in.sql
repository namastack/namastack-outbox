-- H15a: status-based partial per-key index (PG). Serves status = 'NEW' (G2) and the G1 predecessor probe if G1 uses
-- status IN ('NEW','FAILED') (H09). Add-only; the replace variant also drops record_key_created.
CREATE INDEX IF NOT EXISTS idx_outbox_record_open_key_created
    ON outbox_record (record_key, created_at, id) WHERE status IN ('NEW', 'FAILED');
