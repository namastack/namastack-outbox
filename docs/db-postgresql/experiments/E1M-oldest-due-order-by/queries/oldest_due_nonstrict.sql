-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: DefaultJdbcOutboxRecordQueries.findOldestDueRetry (candidate: ORDER BY + LIMIT instead of MIN, experiment E1M)
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now ORDER BY o.next_retry_at LIMIT :limit
