-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionStatus (query-package, omin-ref)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
