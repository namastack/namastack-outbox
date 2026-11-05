-- id: count_new
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByStatus (query-package, mdb-opt)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
