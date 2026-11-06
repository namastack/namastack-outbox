-- id: count_new
-- group: MON
-- kind: read
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.countByStatus (queries-class, v1)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
