-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/oldest_created/min-agg, Q-pgx/oldest_created/oc_min
-- class: ok
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
