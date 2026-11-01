-- Phase 2: apply only after EVERY instance uses the optimized JDBC reader.
-- Run outside a transaction. Review application-specific queries before removing their indexes.
-- These removals match the measured three-index layout (ID primary key plus two partial indexes).
-- Retain status or partition indexes if management/count/cleanup query latency is more important.
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_completed_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_completed_created_id;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_partition_status_retry;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status_retry;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status;
