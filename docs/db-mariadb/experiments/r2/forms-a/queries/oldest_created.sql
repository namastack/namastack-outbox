-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/oldest_created/ord-limit-id
-- class: ok
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at, id LIMIT :limit
