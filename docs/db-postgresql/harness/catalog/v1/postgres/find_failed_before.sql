-- id: find_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: PostgresJdbcOutboxRecordQueries.findByStatusAndNextRetryBefore (queries-class, v1)
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit
