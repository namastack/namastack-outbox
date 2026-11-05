-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- note: form union-per-status; needs code change
SELECT :status1 AS status, COUNT(*) FROM outbox_record WHERE status = :status1 UNION ALL SELECT :status2, COUNT(*) FROM outbox_record WHERE status = :status2
