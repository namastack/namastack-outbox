-- id: ids_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: SqlServerJdbcOutboxRecordQueries.findIdsByStatusAndNextRetryBefore (query-package, omin-ref)
SELECT id FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
