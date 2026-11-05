-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByStatus (query-package, mdb-opt)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
