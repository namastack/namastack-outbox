-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.oldestCreatedAtByPartitionsStatusQuery (repository-fields, main)
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
