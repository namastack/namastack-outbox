-- Scenario D, hot keys: 1k keys x 1000 records = 1M rows (long per-key histories).
-- keys 1..100 (10%): last 200 records (n 801..1000) NEW -> 20k NEW (2%).
-- keys 1..10: record n=800 FAILED; keys 1..5: NEW records next_retry_at = now + 1h.
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN k <= 100 AND n >= 801 THEN 'NEW' WHEN k <= 10 AND n = 800 THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  CASE WHEN (k <= 100 AND n >= 801) OR (k <= 10 AND n = 800) THEN NULL ELSE ts + interval '1 second' END,
  CASE WHEN k <= 10 AND n = 800 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 5 AND n >= 801 THEN bn.now + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM bench_now bn, generate_series(1, 1000) n, generate_series(1, 1000) k,
  LATERAL (SELECT bn.now - interval '30 days' + (n * 1000 + k) * interval '50 milliseconds' AS ts) t
ORDER BY n, k;
