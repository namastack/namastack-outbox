-- Scenario E, blocked keys: like A (keys 1..2000 with 3 NEW records each), but 20% of the
-- keys with NEW records (keys 1..400) have a FAILED head (n=17) that blocks them when
-- ignoreRecordKeysWithPreviousFailure=true. keys 1..100: NEW records next_retry_at = now + 1h.
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN k <= 2000 AND n >= 18 THEN 'NEW' WHEN k <= 400 AND n = 17 THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  CASE WHEN (k <= 2000 AND n >= 18) OR (k <= 400 AND n = 17) THEN NULL ELSE ts + interval '1 second' END,
  CASE WHEN k <= 400 AND n = 17 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 100 AND n >= 18 THEN bn.now + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM bench_now bn, generate_series(1, 20) n, generate_series(1, 50000) k,
  LATERAL (SELECT bn.now - interval '30 days' + (n * 50000 + k) * interval '50 milliseconds' AS ts) t
ORDER BY n, k;
