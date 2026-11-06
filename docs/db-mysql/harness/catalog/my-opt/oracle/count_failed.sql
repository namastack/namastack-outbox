-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByStatus (query-package, my-opt)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
