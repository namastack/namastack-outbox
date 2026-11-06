-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: H2JdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, cmp-jdbc-opt)
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at FETCH FIRST :limit ROWS ONLY
