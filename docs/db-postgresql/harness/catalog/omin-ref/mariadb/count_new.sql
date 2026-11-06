-- id: count_new
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatus (query-package, omin-ref)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
