-- id: find_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: catalog v1 + literal completed_at IS NULL (R2 A6)
SELECT * FROM outbox_record WHERE status = :status AND completed_at IS NULL AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit
