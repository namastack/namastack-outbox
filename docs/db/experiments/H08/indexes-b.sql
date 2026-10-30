-- H08b: fallback set if H06's probe on the non-partial index is slower or G2 adopts the partial key index (H12):
-- H04's two partial indexes + drop of record_key_created. Run with experiments/H05/queries.
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
