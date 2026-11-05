-- id: ids_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: OracleJdbcOutboxRecordQueries.findIdsByStatusAndNextRetryBefore (query-package, omin-ref)
SELECT id FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at FETCH FIRST :limit ROWS ONLY
