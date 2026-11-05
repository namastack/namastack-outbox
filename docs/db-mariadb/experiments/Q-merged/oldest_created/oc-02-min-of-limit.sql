-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/oldest_created/min-over-limited, Q-pgx/oldest_created/oc_min_of_limit
-- class: ok
SELECT MIN(created_at) FROM (SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT :limit) t
