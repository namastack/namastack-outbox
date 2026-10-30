-- H09: does Postgres prove partial-index implication for status-based predicates?
-- Run on a container with a H09a or H09b index state. Output: the node line per statement.
\set ON_ERROR_STOP off
SET max_parallel_workers_per_gather = 0;
\echo == literal status = 'NEW'
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE status = 'NEW' ORDER BY created_at, id LIMIT 10;
\echo == literal status = 'FAILED'
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE status = 'FAILED' ORDER BY created_at, id LIMIT 10;
\echo == literal status IN (NEW, FAILED)
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE status IN ('NEW','FAILED') ORDER BY created_at, id LIMIT 10;
\echo == literal status <> COMPLETED
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE status <> 'COMPLETED' ORDER BY created_at, id LIMIT 10;
\echo == negative control: literal status = 'COMPLETED' (must NOT use the partial index)
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE status = 'COMPLETED' ORDER BY created_at, id LIMIT 10;
\echo == completed_at IS NULL (H04 predicate, not implied by a status-based index)
EXPLAIN (COSTS OFF) SELECT id FROM outbox_record WHERE completed_at IS NULL ORDER BY created_at, id LIMIT 10;
\echo == generic plan, status = $1 (unknown at plan time)
SET plan_cache_mode = force_generic_plan;
PREPARE g1(varchar) AS SELECT id FROM outbox_record WHERE status = $1 ORDER BY created_at, id LIMIT 10;
EXPLAIN (COSTS OFF) EXECUTE g1('NEW');
\echo == generic plan, status = $1 AND status IN (NEW, FAILED) (explicit predicate)
PREPARE g2(varchar) AS SELECT id FROM outbox_record WHERE status = $1 AND status IN ('NEW','FAILED') ORDER BY created_at, id LIMIT 10;
EXPLAIN (COSTS OFF) EXECUTE g2('NEW');
\echo == generic plan, status = $1 AND status <> COMPLETED (explicit predicate)
PREPARE g3(varchar) AS SELECT id FROM outbox_record WHERE status = $1 AND status <> 'COMPLETED' ORDER BY created_at, id LIMIT 10;
EXPLAIN (COSTS OFF) EXECUTE g3('NEW');
\echo == custom plan with bound parameter (pgjdbc after the first executions)
SET plan_cache_mode = force_custom_plan;
PREPARE c1(varchar) AS SELECT id FROM outbox_record WHERE status = $1 ORDER BY created_at, id LIMIT 10;
EXPLAIN (COSTS OFF) EXECUTE c1('NEW');
