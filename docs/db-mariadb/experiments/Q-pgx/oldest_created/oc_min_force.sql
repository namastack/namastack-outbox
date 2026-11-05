-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- note: form oc_min_force (proposer pgx)
SELECT MIN(created_at) FROM outbox_record FORCE INDEX (idx_outbox_record_status_completed_created_id) WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
