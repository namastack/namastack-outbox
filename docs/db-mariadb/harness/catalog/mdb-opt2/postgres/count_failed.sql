-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.countByStatus (query-package, mdb-opt2)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
