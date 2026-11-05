-- id: find_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: SqlServerJdbcOutboxRecordQueries.findByStatusAndCompletedBefore (query-package, cmp-jdbc-opt)
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
