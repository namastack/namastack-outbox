-- id: find_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: SqlServerJdbcOutboxRecordQueries.findByStatusAndNextRetryBefore (query-package, mdb-final)
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
