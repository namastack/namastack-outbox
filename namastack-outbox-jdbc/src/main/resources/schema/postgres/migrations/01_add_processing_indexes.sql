-- Phase 1: apply before deploying the optimized JDBC reader.
-- Run outside a transaction because CREATE INDEX CONCURRENTLY cannot run in a transaction block.
-- Adapt table/index names when using a custom schema or table prefix.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_key_incomplete_group
    ON outbox_record (record_key, (completed_at IS NULL) DESC)
    WHERE completed_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_created_id_incomplete
    ON outbox_record (created_at, id, partition_no, status, next_retry_at)
    INCLUDE (record_key)
    WHERE completed_at IS NULL;

CREATE STATISTICS IF NOT EXISTS outbox_record_status_completed_stx (mcv)
    ON status, completed_at FROM outbox_record;

ANALYZE outbox_record;
