-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.countByPartitionStatus (query-package, cmp-jdbc-opt)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
