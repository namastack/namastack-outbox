-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByStatuses (query-package, mdb-final)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
