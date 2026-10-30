-- H07a (lean, portable shape): non-partial outer index; incomplete rows are the contiguous
-- completed_at IS NULL section (NULLS LAST in Postgres/Oracle, first in SQL Server/MySQL), in (created_at, id) order.
-- Probes use the existing idx_outbox_record_record_key_completed_created. No partial index at all.
CREATE INDEX IF NOT EXISTS idx_outbox_record_completed_created
    ON outbox_record (completed_at, created_at, id);
-- in-place transition from an H04/H05/H06 state:
DROP INDEX IF EXISTS idx_outbox_record_incomplete_created;
DROP INDEX IF EXISTS idx_outbox_record_incomplete_key;
