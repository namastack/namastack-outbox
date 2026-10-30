-- H31 variant b (modify): idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)
--                      -> (partition_no, status, created_at)
-- Only valid if G1 does not need next_retry_at in this index (baseline: the key queries never use it with 64 partitions).
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
CREATE INDEX IF NOT EXISTS idx_outbox_record_partition_status_created ON outbox_record (partition_no, status, created_at);
