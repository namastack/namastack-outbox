-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.recordKeysQueryWithPreviousFailureFilterTemplate (repository-fields, my-main)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND ( older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id) ) ) ORDER BY o.created_at ASC, o.id ASC FETCH FIRST :limit ROWS ONLY
