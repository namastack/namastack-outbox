-- H06: only the outer partial index of H04. Head-of-key probes use the EXISTING
-- idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at).
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
-- in-place transition from the H04/H05 state:
DROP INDEX IF EXISTS idx_outbox_record_incomplete_key;
