-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.countByPartitionsStatusQuery (repository-fields, mdb-main)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
