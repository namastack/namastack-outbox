-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/oldest_created/ord-limit-force-idx1, Q-pgx/oldest_created/oc_order_force
-- class: hint
SELECT created_at FROM outbox_record FORCE INDEX (idx_outbox_record_status_completed_created_id) WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT :limit
