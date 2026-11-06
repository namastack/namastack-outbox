-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByStatus (query-package, omin-ref)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
