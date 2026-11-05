-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- note: form cs_split_null (proposer pgx)
SELECT u.status, COUNT(*) FROM ( SELECT status FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL UNION ALL SELECT status FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NOT NULL ) u GROUP BY u.status
