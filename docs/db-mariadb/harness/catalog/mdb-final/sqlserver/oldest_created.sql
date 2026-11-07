-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-final)
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
