-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: SqlServerJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (queries-class, v1)
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
