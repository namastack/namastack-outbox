-- id: upd_retry
-- group: WRITE
-- kind: write
-- maxrows: none
-- source: JdbcOutboxRecordRepository.updateProcessingStateQuery (repository-fields, cmp-main)
UPDATE outbox_record SET status = :status, completed_at = :completedAt, failure_count = :failureCount, failure_reason = :failureReason, next_retry_at = :nextRetryAt WHERE id = :id
