-- id: count_new
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByStatus (query-package, mdb-final)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
