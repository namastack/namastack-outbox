-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/count_partitions/use-idx4
-- class: hint
SELECT COUNT(*) FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE partition_no IN (:partitions) AND status = :status
