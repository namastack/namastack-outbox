-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.countByStatuses (query-package, omin-ref)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
