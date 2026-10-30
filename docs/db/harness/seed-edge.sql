-- Edge-case seed for semantic-equivalence checks (compare-results.sh), not for timing.
-- Insert-only, keys are prefixed 'edge-', so it can run standalone (start-postgres.sh x edge)
-- or on top of a scenario (psql < seed-edge.sql, then ANALYZE).
--
-- Only keys whose partition is < 128 are inserted, so partitions 128..255 are EMPTY when this
-- seed runs alone (use parts=256 to include them in the IN list).
--
-- Patterns per key (g = k % 10), 6 record slots n = 1..6, per-key base time = now - 1 day + k seconds
-- (records of one key are within the same second, so keys never tie on MIN(created_at)
-- unless -v key_ties=1 is passed):
--   g0  NEW x3, all due
--   g1  FAILED head, then NEW x3 (blocked with filter on)
--   g2  NEW head not due (next_retry_at = now + 1h), then 2 due NEW (head blocks the key)
--   g3  due NEW, not-due NEW, due NEW (mixed next_retry_at)
--   g4  3 NEW with identical created_at, ids in reverse insertion order (tie broken by id)
--   g5  FAILED and NEW with identical created_at, FAILED id < NEW id (FAILED is the predecessor)
--   g6  FAILED and NEW with identical created_at, FAILED id > NEW id (NEW is the head; the NEW
--       after it is blocked by the FAILED)
--   g7  COMPLETED history, then NEW x2, then a COMPLETED newer than the NEW records
--   g8  only COMPLETED (no incomplete record)
--   g9  FAILED only (no NEW)
-- With -v key_ties=1 keys k = 1..50 share one base time (MIN(created_at) ties across keys,
-- whose relative order is unspecified by the contract: compare with that in mind).
\if :{?key_ties}
\else
\set key_ties 0
\endif
INSERT INTO outbox_record
SELECT s.id, s.status, s.record_key, 'com.example.OrderEvent', repeat('y', 200), '{}', s.created_at,
  CASE WHEN s.status = 'COMPLETED' THEN s.created_at + interval '1 second' END,
  CASE WHEN s.status = 'FAILED' THEN 5 ELSE 0 END, NULL,
  s.next_retry_at, abs(hashtext(s.record_key)) % 256, 'com.example.Handler#handle'
FROM (
  SELECT 'edge-' || k AS record_key, k, n, k % 10 AS g,
    -- default id: zero-padded, so id order = n order
    CASE
      WHEN k % 10 = 4 THEN 'edge-' || k || '-' || (9 - n)            -- reverse id order for ties
      WHEN k % 10 = 5 AND n = 1 THEN 'edge-' || k || '-a'            -- FAILED, smaller id
      WHEN k % 10 = 5 AND n = 2 THEN 'edge-' || k || '-b'            -- NEW, larger id
      WHEN k % 10 = 6 AND n = 1 THEN 'edge-' || k || '-z'            -- FAILED, larger id
      WHEN k % 10 = 6 AND n = 2 THEN 'edge-' || k || '-b'            -- NEW, smaller id
      ELSE 'edge-' || k || '-' || n
    END AS id,
    CASE k % 10
      WHEN 0 THEN 'NEW'
      WHEN 1 THEN CASE WHEN n = 1 THEN 'FAILED' ELSE 'NEW' END
      WHEN 2 THEN 'NEW'
      WHEN 3 THEN 'NEW'
      WHEN 4 THEN 'NEW'
      WHEN 5 THEN CASE WHEN n = 1 THEN 'FAILED' ELSE 'NEW' END
      WHEN 6 THEN CASE WHEN n = 1 THEN 'FAILED' ELSE 'NEW' END
      WHEN 7 THEN CASE WHEN n IN (3, 4) THEN 'NEW' ELSE 'COMPLETED' END
      WHEN 8 THEN 'COMPLETED'
      WHEN 9 THEN 'FAILED'
    END AS status,
    base + CASE
      WHEN k % 10 = 4 THEN interval '0'                                 -- all three tie
      WHEN k % 10 IN (5, 6) AND n IN (1, 2) THEN interval '0'           -- FAILED/NEW tie
      ELSE n * interval '10 milliseconds'
    END AS created_at,
    CASE
      WHEN k % 10 = 2 AND n = 1 THEN bn.now + interval '1 hour'
      WHEN k % 10 = 3 AND n = 2 THEN bn.now + interval '1 hour'
      ELSE base + n * interval '10 milliseconds'
    END AS next_retry_at
  FROM bench_now bn,
    generate_series(1, 800) k,
    LATERAL (SELECT bn.now - interval '1 day'
                    + CASE WHEN :key_ties = 1 AND k <= 50 THEN interval '0' ELSE k * interval '1 second' END
                    AS base) b,
    LATERAL generate_series(1, CASE WHEN k % 10 IN (4, 5, 6) THEN 3 WHEN k % 10 = 7 THEN 5 ELSE 4 END) n
  WHERE abs(hashtext('edge-' || k)) % 256 < 128
) s
WHERE NOT (s.g = 0 AND s.n = 4)  -- g0: exactly 3 NEW
ORDER BY s.created_at, s.id;
