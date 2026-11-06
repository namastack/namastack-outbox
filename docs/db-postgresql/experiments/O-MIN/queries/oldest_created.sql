-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: PostgresJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus at a06a74c0, with MIN instead of ORDER BY + LIMIT (user idea)
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
