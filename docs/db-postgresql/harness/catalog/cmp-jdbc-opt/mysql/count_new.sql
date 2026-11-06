-- id: count_new
-- group: MON
-- kind: read
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.countByStatus (query-package, cmp-jdbc-opt)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
