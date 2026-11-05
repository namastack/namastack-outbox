-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- note: form use-idx4
SELECT COUNT(*) FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE partition_no IN (:partitions) AND status = :status
