-- id: ids_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: SqlServerJdbcOutboxRecordQueries.findIdsByStatusAndCompletedBefore (query-package, mdb-final)
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
