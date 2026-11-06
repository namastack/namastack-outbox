-- id: count_completed
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByStatus (query-package, omin-ref)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
