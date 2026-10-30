CREATE INDEX idx_outbox_rec_status_created ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key);
