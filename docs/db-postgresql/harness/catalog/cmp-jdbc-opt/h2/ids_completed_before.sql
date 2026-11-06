-- id: ids_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: H2JdbcOutboxRecordQueries.findIdsByStatusAndCompletedBefore (query-package, cmp-jdbc-opt)
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at FETCH FIRST :limit ROWS ONLY
