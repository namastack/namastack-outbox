-- H40 variant replace: idx_outbox_record_status (status) -> (status, completed_at)
-- (status-leading prefix kept for every `status = ?` query; NEW/FAILED rows have completed_at NULL, so their
--  entries are equal keys and stay deduplicated like in the single-column index)
DROP INDEX IF EXISTS idx_outbox_record_status;
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_completed ON outbox_record (status, completed_at);
