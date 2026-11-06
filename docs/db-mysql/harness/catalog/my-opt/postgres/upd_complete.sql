-- id: upd_complete
-- group: WRITE
-- kind: write
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.updateProcessingState (query-package, my-opt)
UPDATE outbox_record SET status = :status, completed_at = :completedAt, failure_count = :failureCount, failure_reason = :failureReason, next_retry_at = :nextRetryAt WHERE id = :id
