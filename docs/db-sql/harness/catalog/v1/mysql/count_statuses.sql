-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.countByStatuses (queries-class, v1)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
