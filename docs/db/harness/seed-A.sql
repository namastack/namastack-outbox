-- Scenario A, steady state: 50k keys x 20 records = 1M rows, history retained.
-- keys 1..2000: last 3 records (n 18..20) NEW            -> 6000 NEW
-- keys 1..200 : record n=17 FAILED (completed_at NULL)   -> blocks the NEW records with the filter on
-- keys 1..100 : NEW records have next_retry_at = now + 1h (not due, still block)
-- Rows are inserted in created_at order (heap order = insertion order, as in production).
-- "now" is bench_now (fixed), not now().
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN k <= 2000 AND n >= 18 THEN 'NEW' WHEN k <= 200 AND n = 17 THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  CASE WHEN (k <= 2000 AND n >= 18) OR (k <= 200 AND n = 17) THEN NULL ELSE ts + interval '1 second' END,
  CASE WHEN k <= 200 AND n = 17 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 100 AND n >= 18 THEN bn.now + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM bench_now bn, generate_series(1, 20) n, generate_series(1, 50000) k,
  LATERAL (SELECT bn.now - interval '30 days' + (n * 50000 + k) * interval '50 milliseconds' AS ts) t
ORDER BY n, k;
