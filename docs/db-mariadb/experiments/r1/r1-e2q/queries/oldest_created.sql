-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: round 1a pack r1-qf12 (E2): per-partition MIN, then MIN over partitions (loose index scan expected)
-- class: ok
SELECT MIN(m) FROM (SELECT MIN(created_at) AS m FROM outbox_record WHERE status = :status AND completed_at IS NULL AND partition_no IN (:partitions) GROUP BY partition_no) t
