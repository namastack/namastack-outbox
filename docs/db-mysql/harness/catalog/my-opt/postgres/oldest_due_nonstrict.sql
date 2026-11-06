-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: PostgresJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, my-opt)
SELECT MIN(next_retry_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND next_retry_at <= :now AND completed_at IS NULL
