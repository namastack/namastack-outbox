-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
