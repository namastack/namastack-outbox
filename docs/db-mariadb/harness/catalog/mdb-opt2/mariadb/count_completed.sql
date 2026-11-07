-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatus (query-package, mdb-opt2)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
