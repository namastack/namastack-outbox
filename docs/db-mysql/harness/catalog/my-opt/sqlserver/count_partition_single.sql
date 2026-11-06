-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.countByPartitionStatus (query-package, my-opt)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
