-- id: count_statuses_all
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByStatuses (query-package, omin-ref)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
