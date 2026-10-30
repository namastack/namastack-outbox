-- method: countRecordsByPartition(partition, NEW)
-- sql: countByPartitionStatusQuery (partition 0)
-- class: periodic (metrics module OutboxPartitionMetricsProvider, once per owned partition; also the SPI
--        default of countRecordsByPartitions)
\ir _params.sql
:explain SELECT COUNT(*) FROM outbox_record
WHERE partition_no = 0 AND status = :'status';
