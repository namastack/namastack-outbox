-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueStrict (query-package, mdb-opt)
-- note: form win-min
SELECT MIN(h.next_retry_at) FROM (SELECT r.status, r.partition_no, r.next_retry_at, r.completed_at, ROW_NUMBER() OVER (PARTITION BY r.record_key ORDER BY r.created_at, r.id) AS rn FROM outbox_record r WHERE r.completed_at IS NULL) h WHERE h.rn = 1 AND h.status = :status AND h.partition_no IN (:partitions) AND h.next_retry_at <= :now
