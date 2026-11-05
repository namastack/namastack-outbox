-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByStatuses (query-package, cmp-jdbc-opt)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
