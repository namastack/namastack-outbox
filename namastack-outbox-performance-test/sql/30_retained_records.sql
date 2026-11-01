-- Completed fixtures simulate a mature outbox without spending minutes delivering warm-up records.
-- Their IDs cannot match the producer's <run-id>-record-<sequence> format.
INSERT INTO outbox_record
    (id, status, record_key, record_type, payload, context, created_at, completed_at,
     failure_count, failure_reason, next_retry_at, partition_no, handler_id)
SELECT '~retained~-' || n,
       'COMPLETED',
       'payment:' || lpad(((n - 1) / 10)::text, 9, '0'),
       'io.namastack.performance.consumer.PaymentRequestedEvent',
       '{"paymentId":"retained","orderId":"retained","customerId":"retained","amount":129.90,"currency":"EUR"}',
       '{"benchmarkRunId":"~retained~"}',
       now() - INTERVAL '1 day', now() - INTERVAL '1 day',
       0, NULL, now() - INTERVAL '1 day', ((n - 1) % 256)::integer,
       'io.namastack.performance.consumer.PaymentRequestedEventHandler#handle(io.namastack.performance.consumer.PaymentRequestedEvent,io.namastack.outbox.handler.OutboxRecordMetadata)'
FROM generate_series(1, :retained_records::bigint) AS n;

-- Exclude fixture creation's dirty-page flush and statistics update from the measured workload.
CHECKPOINT;
ANALYZE outbox_record;
