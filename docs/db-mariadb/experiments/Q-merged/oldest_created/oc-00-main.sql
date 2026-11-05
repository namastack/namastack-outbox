-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.oldestCreatedAtByPartitionsStatusQuery (repository-fields, mdb-main)
-- origin: catalog mdb-main (main SQL)
-- class: ok
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
