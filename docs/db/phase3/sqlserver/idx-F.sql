-- F: filtered (Postgres-partial equivalent); completed_at in INCLUDE avoids a base-table lookup to re-check the filter column + drop prefix-duplicate
CREATE INDEX idx_outbox_record_incomplete_created ON outbox_record (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL;
DROP INDEX idx_outbox_record_record_key_created ON outbox_record;
UPDATE STATISTICS outbox_record WITH FULLSCAN;
