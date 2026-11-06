-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: JdbcOutboxRecordRepository.recordKeysQueryWithPreviousFailureFilterTemplate (repository-fields, main)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND ( older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id) ) ) GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC
