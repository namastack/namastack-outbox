-- H32 variant i: G1-shaped partial index on incomplete rows (ASSUMED shape; replace with G1's accepted DDL),
-- with idx_outbox_record_partition_status_retry dropped (the case H32 has to cover).
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_partition_created
    ON outbox_record (partition_no, created_at, id) WHERE completed_at IS NULL;
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
