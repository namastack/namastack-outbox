-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatus (query-package, mdb-final)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
