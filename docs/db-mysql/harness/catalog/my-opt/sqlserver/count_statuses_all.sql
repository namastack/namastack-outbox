-- id: count_statuses_all
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByStatuses (query-package, my-opt)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
