-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByStatus (query-package, mdb-final)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
