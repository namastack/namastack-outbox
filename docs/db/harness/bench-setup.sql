-- Harness helper objects (not part of the library schema).
-- bench_now holds the fixed "now" used by every seed and query, so results are reproducible
-- and independent of the wall clock: next_retry_at "1 hour in the future" means bench_now + 1h.
CREATE TABLE IF NOT EXISTS bench_now (now timestamptz NOT NULL);
TRUNCATE bench_now;
INSERT INTO bench_now VALUES ('2026-01-01 00:00:00+00');
