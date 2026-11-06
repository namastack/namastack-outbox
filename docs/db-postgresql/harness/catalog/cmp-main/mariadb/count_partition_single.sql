-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.countByPartitionStatusQuery (repository-fields, cmp-main)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
