-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-pgx/count_partitions/cp_incomplete_force
-- class: status-conditional
SELECT COUNT(*) FROM outbox_record FORCE INDEX (idx_outbox_record_status_completed_partition) WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
