-- method: countRecordsByPartition(partition, NEW)
-- sql: countByPartitionStatusQuery (partition 0) + redundant `AND completed_at IS NULL` (H32)
-- class: periodic (deprecated metrics module, OutboxPartitionMetricsProvider: once per owned partition per gauge read)
\ir _params.sql
:explain SELECT COUNT(*) FROM outbox_record
WHERE partition_no = 0 AND status = :'status' AND completed_at IS NULL;
