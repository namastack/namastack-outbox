-- method: update(record), save(record) (first statement)
-- sql: updateRecordQuery (full row update; record_key/partition_no/created_at rewritten with the same values)
-- class: admin/SPI (no caller in the library's main code)
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
:explain UPDATE outbox_record
SET status = 'COMPLETED', record_key = :'sample_key', record_type = 'com.example.OrderEvent', payload = repeat('x', 500), context = '{}',
    partition_no = abs(hashtext(:'sample_key')) % 256, created_at = :'sample_created',
    completed_at = :'now_ts', failure_count = 0,
    failure_reason = NULL, next_retry_at = :'now_ts', handler_id = 'com.example.Handler#handle'
WHERE id = :'sample_id';
ROLLBACK;
