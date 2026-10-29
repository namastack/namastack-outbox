-- Experimental delivery index: old completed records never enter it.
-- Paired with limited ordered-head selection so PostgreSQL can stop scanning early.
CREATE INDEX IF NOT EXISTS idx_outbox_record_ready_order
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, record_key, next_retry_at)
    WHERE status = 'NEW';
