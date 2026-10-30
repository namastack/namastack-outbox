-- method: create(record) (OutboxService.schedule); save(record) also runs it when the UPDATE matched 0 rows
-- sql: insertRecordQuery
-- class: hot (one per scheduled record and handler, in the caller's transaction)
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
\if :generic
PREPARE q(varchar, varchar, varchar, varchar, text, text, int, timestamptz, timestamptz, int, varchar, timestamptz, varchar) AS
INSERT INTO outbox_record
(id, status, record_key, record_type, payload, context, partition_no,
 created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13);
:explain EXECUTE q('bench-insert-1', 'NEW', 'order-bench', 'com.example.OrderEvent', repeat('x', 500), '{}',
  abs(hashtext('order-bench')) % 256, :'now_ts', NULL, 0, NULL, :'now_ts', 'com.example.Handler#handle');
DEALLOCATE q;
\else
:explain INSERT INTO outbox_record
(id, status, record_key, record_type, payload, context, partition_no,
 created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
VALUES ('bench-insert-1', 'NEW', 'order-bench', 'com.example.OrderEvent', repeat('x', 500), '{}',
 abs(hashtext('order-bench')) % 256, :'now_ts', NULL, 0, NULL, :'now_ts', 'com.example.Handler#handle');
\endif
ROLLBACK;
