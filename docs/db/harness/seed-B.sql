-- Scenario B, backlog: like A, but keys 1..25000 have their last 8 records (n 13..20) NEW -> 200k NEW.
-- keys 1..200: record n=12 FAILED; keys 1..100: NEW records next_retry_at = now + 1h.
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN k <= 25000 AND n >= 13 THEN 'NEW' WHEN k <= 200 AND n = 12 THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  CASE WHEN (k <= 25000 AND n >= 13) OR (k <= 200 AND n = 12) THEN NULL ELSE ts + interval '1 second' END,
  CASE WHEN k <= 200 AND n = 12 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 100 AND n >= 13 THEN bn.now + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM bench_now bn, generate_series(1, 20) n, generate_series(1, 50000) k,
  LATERAL (SELECT bn.now - interval '30 days' + (n * 50000 + k) * interval '50 milliseconds' AS ts) t
ORDER BY n, k;
