-- id: ids_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: MySqlJdbcOutboxRecordQueries.findIdsByStatusAndCompletedBefore (query-package, omin-ref)
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit
