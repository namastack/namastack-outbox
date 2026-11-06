-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.oldestDueNextRetryAtQueryWithPreviousFailureFilter (repository-fields, main)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND ( older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id) ) )
