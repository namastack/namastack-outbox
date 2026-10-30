-- Scenario C, delete-completed mode: COMPLETED rows are deleted on success, only incomplete rows remain.
-- 50k keys x 4 NEW records (n 17..20) = 200k NEW, plus keys 1..200 with a FAILED record (n=16)
-- before their NEW records, and keys 1..100 with NEW records next_retry_at = now + 1h.
-- Total 200,200 rows.
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN n >= 17 THEN 'NEW' ELSE 'FAILED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  NULL,
  CASE WHEN n = 16 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 100 AND n >= 17 THEN bn.now + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM bench_now bn, generate_series(16, 20) n, generate_series(1, 50000) k,
  LATERAL (SELECT bn.now - interval '30 days' + (n * 50000 + k) * interval '50 milliseconds' AS ts) t
WHERE n >= 17 OR k <= 200
ORDER BY n, k;
