-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, my-opt)
SELECT next_retry_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND next_retry_at <= :now ORDER BY next_retry_at LIMIT :limit
