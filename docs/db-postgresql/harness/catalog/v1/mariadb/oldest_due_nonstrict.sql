-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MySqlJdbcOutboxRecordQueries.oldestDueNextRetryAtWithoutPreviousFailureFilter (queries-class, v1)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
