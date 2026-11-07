-- id: count_statuses_all
-- group: MON
-- kind: read
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-final)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
