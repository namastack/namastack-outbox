-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.countByStatus (query-package, my-opt)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
