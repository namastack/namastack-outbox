-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByStatus (queries-class, v1)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
