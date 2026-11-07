-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- origin: Q-indep/count_statuses/split-null
-- class: ok
SELECT status, SUM(c) AS cnt FROM (SELECT status, COUNT(*) AS c FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status UNION ALL SELECT status, COUNT(*) AS c FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NOT NULL GROUP BY status) t GROUP BY status
