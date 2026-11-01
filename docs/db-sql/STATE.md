# State ledger

Maintained by the orchestrator. Update after every step.

## Current phase

Phase 3+4 — other dialects main+v1 **stopped by the user** (2026-11-01, to be restarted later by the user). PostgreSQL main+v1 done (vacuumed rerun).

## Environment

- Machine / CPU / RAM: Intel i7-8700K (6C/12T), 15 GiB RAM, Linux 7.0.0-38
- Docker version, container CPU/memory limits: Docker 29.9.2; limits set by harness (phase 2)
- DB images and versions:

## Noise threshold

| Query group | Threshold | Source |
|---|---|---|
| HOT | 30 % (p90 spread 28 %, max 47 %) | `results/noise-postgres.md` |
| WRITE | 30 % (p90 26 %, max 51 %) | same |
| MON | 20 % (p90 18 %, max 73 %) | same |
| RET | 20 % (p90 14 %; max 361 % = main `find_completed_before` S4, 5 reps, excluded) | same |
| ADMIN | 20 % (p90 17 %) | same |
| any query with median < 0.3 ms | absolute 0.15 ms (p90 abs diff ≤ 0.14 ms) | same |

A difference counts only if it exceeds both the relative threshold of its group and 0.15 ms. Spreads are run 1 vs run 2 after a container restart (cold buffers), so these are conservative; experiments compare base vs experiment measured back to back on the same snapshot.

## v1 change list

| Id | Change | Files | Queries affected |
|---|---|---|---|
| V1-0 | Dialect-specific query class `JdbcOutboxRecordQueries` + DB detection (infra for V1-4..V1-9; no SQL change by itself) | jdbc `JdbcOutboxRecordQueries.kt`, `JdbcOutboxRecordRepository.kt`, `JdbcDatabaseType.kt`, factory/autoconfig | all JDBC (indirect) |
| V1-1a | Drop index `(record_key, created_at)` (DDL all dialects + JPA annotation) | schema/*, `OutboxRecordEntity` | pages, find_incomplete_by_key, delete_by_key_status, predecessor checks |
| V1-1b | `(record_key, completed_at, created_at)` → `(…, id)` | schema/*, `OutboxRecordEntity` | keys_strict, keys_nonstrict (v1 form), oldest_due_strict, pages (v1 form) |
| V1-2 | New incomplete-record index: PG partial `(created_at,id) INCLUDE(partition_no,status,next_retry_at,record_key) WHERE completed_at IS NULL`; MSSQL filtered equivalent; H2/MySQL/MariaDB `(completed_at,created_at,id,partition_no,status,next_retry_at,record_key)`; Oracle `(status,created_at,id,partition_no,next_retry_at,record_key)`; JPA `(completedAt,createdAt,id)` | schema/*, `OutboxRecordEntity` | keys_*, oldest_created, oldest_due_* |
| V1-3 | PG `CREATE STATISTICS (mcv) ON status, completed_at` | schema/postgres | planner estimates only |
| V1-4 | `findRecordKeysInPartitions` strict: no GROUP BY/MIN, `completed_at IS NULL`, predecessor `earlier.created_at <= o.created_at`, `ORDER BY created_at,id LIMIT` (Oracle `SYS_EXTRACT_UTC`, `FETCH FIRST`) | jdbc queries, JPA repo | keys_strict |
| V1-5 | `findRecordKeysInPartitions` non-strict: same rewrite with NOT EXISTS on earlier due NEW (MSSQL keeps GROUP BY + FETCH) | jdbc queries, JPA repo | keys_nonstrict |
| V1-6 | `oldest_created`: `ORDER BY created_at LIMIT 1` + `completed_at IS NULL` instead of `MIN` (JDBC + JPA) | jdbc queries, JPA repo | oldest_created |
| V1-7 | `oldest_due_strict`: `ORDER BY next_retry_at LIMIT 1` instead of `MIN`, predecessor `<=` added (Oracle `FIRST_ROWS(1)`) (JDBC + JPA) | jdbc queries, JPA repo | oldest_due_strict |
| V1-8 | Pages bulk load: `+ completed_at IS NULL`, `ORDER BY record_key, completed_at, created_at, id`; PG: `unnest` + `CROSS JOIN LATERAL` per key. JPA unchanged | jdbc queries | pages |
| V1-9 | SQL row limit (`LIMIT`/`FETCH`) on the 4 retention find/id queries (JDBC only) | jdbc queries | find_*_before, ids_*_before |
| V1-10 | New `countByStatuses` (one `GROUP BY status` query), JDBC + JPA | api/core SPI, jdbc, jpa | count_statuses |
| V1-11 | Metrics collector: 1 `countByStatuses({NEW,FAILED})` per refresh instead of 3 `countByStatus` (COMPLETED not counted by default); new property `record-statuses` | observability | count_* frequency (depends on V1-10) |

## Experiments ledger

| Id | Hypothesis | Base | Status | Verdict | Report |
|---|---|---|---|---|---|
| E01a/b/c | `(status, created_at, id)` [INCLUDE] on main; a = index only, b = + v1 SQL for keys/oldest, c = b without INCLUDE | main | planned R1 | | `experiments/E01-status-created-main.md` |
| E02a/b/c | v1 queue index → status-leading: a non-partial, b partial `completed_at IS NULL`, c b added alongside | v1 | planned R1 | | `experiments/E02-status-created-v1.md` |
| E11–E14 | leave-one-out DDL: −V1-1a, −V1-1b, −V1-2, −V1-3 | v1 | planned R1 | | `experiments/E1x-v1-ablation-leave-one-out.md` |
| E1Q | leave-one-out query items −V1-4..V1-9 (main SQL per query id, one run) | v1 | planned R1 | | same |

## Decisions

- Phase 1 done: `01-usage-map.md`, `01-usage-map-v1-delta.md` reviewed.
- Retention: main has no scheduler; `deleteCompleted/FailedRecords(before, limit)` treated as an external retention job (measured as RET group); delete-on-complete (`deleteById`) covers S3.
- COMPLETED accumulation (default config) is in scope (S2, S2L).
- Deprecated `metrics` module: only `count_partition_single` measured; per-scrape fan-out (~340 queries) noted, not optimized first.
- `main` limits rows via `setMaxRows` → SQL runner must go through JDBC with `setMaxRows`, not append `LIMIT`.
- Disk ~16 GB free: 10M rows (S2L) only on PostgreSQL with 200 B payload, baseline/v1/v2 only.
- Scenarios fixed in `02-scenarios.md`.
- Phase 2 done: harness reviewed (seeded shared snapshot per dialect, setMaxRows reproduced, 5+20 reps, median/p95, PG plans with real binds). Caveat: pgjdbc server-prepares after 5 executions → generic plans possible in timings while EXPLAIN shows custom plan; check `plan_cache_mode` when timing and plan disagree.
- Phases 3 and 4 are run together per dialect: main and v1 on the same snapshot/container; run 1 order main→v1, run 2 order v1→main (container restarted, cold cache). Reduces gen time and order bias; summaries still written separately (`03-baseline.md`, `04-v1.md`).
- S2L: once per variant, only if ≥13 GB free disk at that point.

- Worktrees: `../outbox-wt-main` (main @ 313eb58), `../outbox-wt-v1` (detached @ e0b09b4).

- Phase 5 candidate list drafted in `experiments/ideas.md` (read-only proposer). Key insight to verify: partial indexes with `status='NEW'` predicate cannot be used by pgjdbc generic plans (`status = $1`); prefer `completed_at IS NULL` predicates (literal in SQL).

- First PG matrix invalid for fine comparisons: PG `prepare` ran only ANALYZE (no VM/hint bits; autovacuum at random times) → spreads up to 24x. Harness fixed (`VACUUM (FREEZE, ANALYZE)`), old results archived in `results/_unvacuumed/`. Qualitative findings from it (to re-confirm): v1 `keys_strict` 4.3 s→1.3 ms (S4), 14.9 s→1.3 ms (S5), 468→1.3 ms (S6); v1 regressions on `keys_nonstrict` (S2, S7), `keys_strict` S7, `count_new/completed` (S4-b100, S5, S8-S4), `count_partitions`; equivalence main vs v1 PASS on all PG scenarios. S2L failed (disk full at 1.2 GB free during prepare) → S2L dropped from the matrix.

- PostgreSQL main+v1 rerun (vacuumed) done: `results/{main,v1}/SUMMARY-postgres.md`, `results/compare-postgres-main-v1.md`, `results/noise-postgres.md`, equivalence PASS on all scenarios (S3 `delete_by_ids` harness artefact: empty id list). No autovacuum inside bench windows.
- Harness: ordered parameter sampling made default (phase 3/4 PG matrix used unordered; only S5 single-key queries differ, 1999 vs 2000 rows).
- Non-PG dialects measured main+v1 once each on the same snapshot; phase 6 then only adds v2 on identical seeded data (plus a main spot check for drift).

- Phase 5 runs per round with one measuring agent per round (all variants share each snapshot; ~6 gens instead of ~70), one report per experiment. Spec: `experiments/round1-spec.md`.
- Early PG observations (from main/v1 data): main's `setMaxRows`-only limits make PG plan full sorts (`find_failed_before` S7 677 ms vs v1 `LIMIT` 3 ms); `find_completed_before` still ~490 ms on v1 (no `(status, completed_at)`-type index); v1 `keys_strict` S7 13 ms (created_at-leading partial index walks 390k FAILED entries).

## Running sub-agents

- none (all agents, driver scripts, JVMs and containers stopped; volumes removed by `bin/cleanup.sh`)

## User decisions

- 2026-11-01: SQL Server `recordKeysWithoutPreviousFailureFilter` override removed (uses the base NOT EXISTS + `ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY` form). Changed in the primary tree (`JdbcOutboxRecordQueries.kt` + `JdbcOutboxRecordQueriesTest`, uncommitted) and in `harness/catalog/v1/sqlserver/keys_nonstrict.sql`. The `../outbox-wt-v1` worktree (e0b09b4) still has the override: do not re-run `extract-catalog.sh v1` without re-applying this. SQL Server not measured yet, so no existing result is affected. To verify: equivalence + timing of `keys_nonstrict` on SQL Server S2/S4/S7 (GROUP BY form vs base form).

## Stopped run (cross-dialect main+v1)

- Stopped by the user while MariaDB S2 `prepare` for v1 was running (MariaDB `prepare` of S2 took ~35 min; MySQL main retention queries ~285 s per execution).
- Completed, both variants: MySQL S1, S2, S8-S2; MariaDB S1, S2, S8-S2 (+ MariaDB main S2 writecost). Missing: MySQL S4, S8-S4, S5, S7, writecost, equivalence; MariaDB rest; SQL Server, Oracle, H2 entirely; per-dialect SUMMARY/compare files not written.
- Harness changes made during that run (see `harness/README.md` "Changes"): >30 s slow-query cap (1 execution, non-executing plan), `--groups` option, driver `bin/matrix-sql.sh` (`SKIPGEN=1`, finished results skipped unless `REDO=1`).
- Phase 5 round 1 not started (spec ready: `experiments/round1-spec.md`).

## Next step

Waiting for the user to restart. Suggested on restart: investigate why MariaDB `prepare` (INSERT ... SELECT into a table with inline secondary indexes) takes ~35 min on S2 (create indexes after the load as on PostgreSQL/H2), then resume the cross-dialect matrix with `bin/matrix-sql.sh` (skips finished results), or start phase 5 round 1 on PostgreSQL first and do the other dialects in phase 6 together with v2.
