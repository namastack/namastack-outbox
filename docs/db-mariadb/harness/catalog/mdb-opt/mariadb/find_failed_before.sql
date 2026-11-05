-- id: find_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: MariaDbJdbcOutboxRecordQueries.findByStatusAndNextRetryBefore (query-package, mdb-opt)
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit
