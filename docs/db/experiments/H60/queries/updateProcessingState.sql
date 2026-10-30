-- method: updateProcessingState(record) (OutboxRecordProcessor.completeRecord, RetryOutboxRecordProcessor,
--         PermanentFailureOutboxRecordProcessor)
-- sql: updateProcessingStateQuery (measured as NEW -> COMPLETED of the head record of the first due key)
-- class: hot (once per processed record)
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
\if :generic
PREPARE q(varchar, timestamptz, int, varchar, timestamptz, varchar) AS
UPDATE outbox_record
SET status = $1, completed_at = $2, failure_count = $3,
    failure_reason = $4, next_retry_at = $5
WHERE id = $6;
:explain EXECUTE q('COMPLETED', :'now_ts', 0, NULL, :'now_ts', :'sample_id');
DEALLOCATE q;
\else
:explain UPDATE outbox_record
SET status = 'COMPLETED', completed_at = :'now_ts', failure_count = 0,
    failure_reason = NULL, next_retry_at = :'now_ts'
WHERE id = :'sample_id';
\endif
ROLLBACK;
