-- id: find_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: MariaDbJdbcOutboxRecordQueries.findByStatusAndCompletedBefore (query-package, my-opt)
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit
