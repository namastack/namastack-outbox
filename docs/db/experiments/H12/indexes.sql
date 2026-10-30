-- H12: partial "incomplete records by key" index, the shape G1 is expected to propose for the
-- predecessor anti join (record_key, created_at, id) WHERE completed_at IS NULL.
-- If G1 settles on a different name/column list, use G1's DDL here instead (see H12 file).
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key_created
    ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;
