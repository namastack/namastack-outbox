-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.oldestDueNextRetryAtQueryWithoutPreviousFailureFilter (repository-fields, my-main)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
