-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByStatus (queries-class, v1)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
