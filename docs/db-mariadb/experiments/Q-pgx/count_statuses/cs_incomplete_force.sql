-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- note: form cs_incomplete_force (proposer pgx)
SELECT status, COUNT(*) FROM outbox_record FORCE INDEX (idx_outbox_record_status_completed_partition) WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
