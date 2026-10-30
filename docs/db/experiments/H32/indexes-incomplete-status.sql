-- H32 variant ii: as variant i, but status available in the index (INCLUDE), so the NEW count can be index-only.
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_partition_created
    ON outbox_record (partition_no, created_at, id) INCLUDE (status) WHERE completed_at IS NULL;
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
