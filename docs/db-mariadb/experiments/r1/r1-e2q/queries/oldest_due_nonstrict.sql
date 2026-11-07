-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, mdb-opt)
-- origin: round 1a pack r1-qf12 (E2): per-partition MIN, then MIN over partitions (loose index scan expected)
-- class: ok
SELECT MIN(m) FROM (SELECT MIN(o.next_retry_at) AS m FROM outbox_record o WHERE o.status = :status AND o.completed_at IS NULL AND o.partition_no IN (:partitions) AND o.next_retry_at <= :now GROUP BY o.partition_no) t
