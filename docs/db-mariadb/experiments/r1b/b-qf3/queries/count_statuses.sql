-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- origin: Q-pgx/count_statuses/cs_incomplete
-- class: status-conditional
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
