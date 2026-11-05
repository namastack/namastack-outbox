-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- note: form use-idx4
SELECT status, COUNT(*) FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE status IN (:statuses) GROUP BY status
