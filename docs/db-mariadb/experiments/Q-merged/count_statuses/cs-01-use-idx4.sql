-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- origin: Q-indep/count_statuses/use-idx4
-- class: hint
SELECT status, COUNT(*) FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE status IN (:statuses) GROUP BY status
