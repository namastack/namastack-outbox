-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, my-opt)
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status ORDER BY created_at LIMIT :limit
