-- id: upd_fail
-- group: WRITE
-- kind: write
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.updateProcessingState (query-package, mdb-opt2)
UPDATE outbox_record SET status = :status, completed_at = :completedAt, failure_count = :failureCount, failure_reason = :failureReason, next_retry_at = :nextRetryAt WHERE id = :id
