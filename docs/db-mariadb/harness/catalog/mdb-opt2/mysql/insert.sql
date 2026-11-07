-- id: insert
-- group: WRITE
-- kind: write
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.insertRecord (query-package, mdb-opt2)
INSERT INTO outbox_record ( id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id ) VALUES ( :id, :status, :recordKey, :recordType, :payload, :context, :partitionNo, :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId )
