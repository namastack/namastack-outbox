-- H03: H02 probe index + covering partial index for the outer side (owned partitions, due records).
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_partition
    ON outbox_record (partition_no, status, next_retry_at)
    INCLUDE (record_key, created_at, id)
    WHERE completed_at IS NULL;
