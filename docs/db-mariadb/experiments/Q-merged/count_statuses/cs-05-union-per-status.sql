-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- origin: Q-indep/count_statuses/union-per-status
-- class: code
SELECT :status1 AS status, COUNT(*) FROM outbox_record WHERE status = :status1 UNION ALL SELECT :status2, COUNT(*) FROM outbox_record WHERE status = :status2
