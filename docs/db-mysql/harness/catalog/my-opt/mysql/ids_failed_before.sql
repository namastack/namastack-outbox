-- id: ids_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: MySqlJdbcOutboxRecordQueries.findIdsByStatusAndNextRetryBefore (query-package, my-opt)
SELECT id FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit
