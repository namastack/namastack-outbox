# State ledger

Maintained by the orchestrator. Update after every step.

## Current phase

Phase 5 (PostgreSQL) done: every query group has a justified recommendation. Recommended set `D3` = `UABC-L` + `(status, partition_no) WHERE completed_at IS NULL` (`experiments/R5-d2-literal.md`); `pages` stays `unnest` + `LATERAL` (`R5-pages-form.md`), `oldest_due_nonstrict` stays `MIN` (`R5-oldest-due.md`), UUIDv7 recommended on write cost, user to decide (`R5-uuidv7.md`; larger key in R5 is a harness load-order artefact, `R5-uuidv7-pkey.md`; `Loader.prepare` fix not applied). Accepted open cost: S8-S5 `keys_strict` ~165 ms. Next: finish phases 3/4.

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
| E01a/b/c | `(status, created_at, id)` [INCLUDE] on main; a = index only, b = + v1 SQL for keys/oldest, c = b without INCLUDE | main | done R1 | mixed: index alone does not fix P1 `keys_strict` (S4 4.4 s→4.0 s); P2 `oldest_created` win; +143 MB, +4–7 % WAL; INCLUDE adds nothing | `experiments/E01-status-created-main.md` |
| E02a/b/c | v1 queue index → status-leading: a non-partial, b partial `completed_at IS NULL`, c b added alongside | v1 | done R1 | **win E02b**: keys_* S2 −41 %, S8-S2 −57 %, S7 13.3→1.2 ms; no write cost (1.9 MB); a = same reads, 143 MB; c = no gain over b. To confirm: generic plan, S4 write cells | `experiments/E02-status-created-v1.md` |
| E11 | −V1-1a (re-add `(record_key, created_at)`) | v1 | done R1 | V1-1a matters (space/WAL only): +67 MB, +2–4 % WAL, latency no difference → keep drop | `experiments/E1x-v1-ablation-leave-one-out.md` |
| E12 | −V1-1b (no `id` in record-key index) | v1 | done R1 | no difference in time; index 77 vs 124 MB → revert candidate (write cost not measured) | same |
| E13 | −V1-2 (no partial queue index) | v1 | done R1 | V1-2 matters: keys S4 1.25→808 ms, S10 p4 1110→659 rec/s; S7 faster without (13.3→8.7) | same |
| E14 | −V1-3 (no extended statistics) | v1 | done R1 | V1-3 matters with custom plans: S2 est 11 vs 1000 actual, keys_* +101/+121 %, S10 p4 1110→770 rec/s; generic plan no difference; ANALYZE +8 % | same |
| E1Q | −V1-4..V1-9 query items | v1 | done R1 | V1-4, V1-5 (regression S8-S2/S7), V1-6, V1-7, V1-8 (moderate), V1-9 (P3) matter; V1-10 no difference alone, V1-11 matters (per refresh S2 30→1.3 ms) | same |
| E1M | `findOldestDueRetry` MIN vs ORDER BY+LIMIT | v1 | done R1 | **keep MIN**: ORDER BY +0.19–0.50 ms (+42–78 %) in all 7 labels, identical EXPLAIN plans (cause unverified) | same |
| E1P | v1 pages: base `IN (...)` instead of PG `unnest`+`LATERAL` | v1 | done R1 | **no difference** (never worse; S4-b1000 −29 %, S5 −30 % via parallel plan); ordered index scan, no Sort → override not necessary; statement-cache churn not measured | `experiments/E1P-pages-default-pg.md` |
| v1b | v1 + E02b index + E1P `pages` | v1 | done R2 | better reference for `keys_*` (S8-S2 keys_strict 1.08 vs 1.97 ms; S7 13.2→1.4 ms); write cost same as v1. **`pages` form unresolved, see below** | `experiments/R2-v1b.md` |
| A1 | v1b − `idx_status` | v1b | done R2 | marginal: no P1 effect, P2 −0.5 ms loss, WAL gain within noise | `experiments/E05-drop-indexes.md` |
| A2 | v1b − `idx_status_retry` | v1b | done R2 | keep: `oldest_due_strict` S8-S4 0.7→527 ms, no WAL saving (round 3 tests partial C instead) | same |
| A5 | v1b − `idx_partition_status_retry` | v1b | done R2 | mixed: WAL −6.6 %, `count_partitions` S8-S4 16→209 ms | same |
| A6 | v1b − all three + literals | v1b | done R2 | not adoptable: WAL −9 %, indexes −30 %, but `oldest_due_strict` seq scan (0.6→259–722 ms) | same |
| K12 | record-key index without `id` | v1b | done R2 | space win 44 MiB, no P1/P2 loss (superseded if E04 adopted) | same |
| E04 | partial record-key index | v1b | done R2 | **win**: P1 equal, indexes 277→160 MB, WAL `upd_complete` −7 %; P3 `delete_by_key_status` S4 0.12→413 ms (accepted by the user rule) | `experiments/E04-partial-record-key.md` |
| MIN | PK + 2 partial indexes + literals | v1b | done R2 | mixed, not acceptable as measured: WAL −9/−16 %, 77 MB, but P2 loss (`oldest_due_strict` S8-S2 0.6→186 ms, `count_*`); literal on `count_statuses` silently loses COMPLETED | same |
| E21–E28 | add-one on main | main | done R2 | V1-1a alone: space/WAL; V1-2 + V1-4/5 only together (S4 keys_strict main 4272, index only 4089, SQL only 807, both 1.25 ms); V1-3 alone no difference; V1-7 alone matters; V1-6 needs queue index; V1-8 only S7 | `experiments/E2x-v1-ablation-add-one.md` |
| R3 stage 0 | `pages` forms in the S10 loop | v1b | done R3 | **Keep `unnest`+`LATERAL`**: 8 of 8 passes fast; `IN`/`= ANY` flip to custom plans walking queue index A with a `record_key` filter (5–17 ms). Parallel hypothesis refuted. Removing `ORDER BY` does not help. E1P verdict withdrawn | `experiments/R3-stage0-pages-loop.md` |
| R3 screen | user's index set A/B/C/D, `partition_no` in key | v1b | done R3 | `keys_*` need only A+B; C required for `oldest_due_*`; D2 > D1; B2, C2 no gain so far; `partition_no` in key rejected (PA1 692 ms, PC1 198 ms); survivors `UABCD2`, `UABC-L`, `UABC-L-NS`, `UAB2C-L`, `UABC2-L` | `experiments/R3-screen.md` |
| E40 | UUIDv7 instead of v4 for record `id` (`OutboxRecord.kt:241`) and for generated record keys (`OutboxService`, `OutboxEventResolver`) (user idea): time-ordered PK inserts append to the right edge → fewer page splits / full-page writes (WAL), smaller and cache-friendlier PK; big expected effect on clustered PKs (MySQL/MariaDB InnoDB, SQL Server) | main, v1 | planned R2 (needs harness `--id-version 7` for gen + inserts) | | `experiments/E40-uuid-v7.md` |

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

- None. Phase 3/4 driver `harness/bin/finish-34.sh` stopped by the user 2026-11-04, to continue later. Resume with the same command (`main` only, resumable, log `results/logs/finish34.log`). Other dialects' bench volumes and images were removed for disk; the driver pulls what it needs.

## Open findings after round 2

- `pages` loop question resolved in round 3 stage 0, see `R3-stage0-pages-loop.md`.
- Plan modes (`R2-plan-modes.md`): ~22 ms `count_*` = generic parallel Gather (confirmed); the three `count_*` share one prepared statement and flip on a cost tie; E1M gap mostly planning time (partly inferred); S4 write cells NOT explained by plan mode (cause open). V1-3 probably matters in production (inferred). Round 1 cells cannot be checked (no `plancache.csv`). Harness changes for round 3 in section 7 of that report.
- Most latency cells are single runs; repeats only S10 (spread up to ±46 %) and write cost of v1, v1b, MIN. S5/S6/S7 full ownership only.

## User decisions

- 2026-11-04: **Phases 3/4 finish with `main` only:** `v1` lost to the phase 5 sets on PostgreSQL, so it is no longer measured on the other dialects. Phase 6 compares `main` with the candidates directly and does its own equivalence. Finished `v1` cells are kept. `VARIANTS="main v1"` on `finish-34.sh` restores it.
- 2026-11-03: **`K3` (capped `keys_strict` candidates) rejected, do not test it or a fallback form:** if the 1000 candidates share one key, the query returns one key instead of `:limit`.
- 2026-11-02: **Run order, without waiting for the user:** screen extension (X0, X1) → print its summary → stage 2 confirm → stage 3 validate → round 4 → further rounds if the orchestrator finds ideas worth checking, up to the phase 5 stop criterion → finish phases 3/4 (see "Next step"). Report after each stage and round. **Do not start phases 6 and 7** without the user.
- 2026-11-02: **Staged runs** for round 3 and later: screen (P1/P2 on S8-S2, S8-S4, S8-S7 + write cost on S2) → confirm (survivors on all labels, partial ownership first, equivalence, S9, S10) → validate (top 2–3, at least twice, order swapped, restart, against v1b; only this gives a final win).
- 2026-11-02: **Partial partition ownership is the default deployment.** Rank partial-ownership results (S8-*, S16-*) first in verdicts; full ownership is a secondary case.
- 2026-11-01: SQL Server `keys_nonstrict` uses the base NOT EXISTS + `ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY` form (no override). `harness/catalog/v1/sqlserver/keys_nonstrict.sql` has it, but the `../outbox-wt-v1` worktree (e0b09b4) still has the override: do not re-run `extract-catalog.sh v1` without re-applying this. To verify: equivalence + timing on SQL Server S2/S4/S7.
- 2026-11-01: **Query priorities for all experiments and recommendations** (user):
  1. **P1 — scheduling and execution** (the hot path): `keys_strict`, `keys_nonstrict`, `pages`, and the writes the processing loop
     does: `insert` (create, in the caller's business transaction), `upd_complete`/`upd_retry`/`upd_fail` (updateProcessingState),
     `delete_by_id` (delete-on-complete). Plus S10 throughput.
  2. **P2 — metrics, only the new observability collector**: `count_statuses` (NEW, FAILED), `count_partitions`, `oldest_created`,
     `oldest_due_strict`/`oldest_due_nonstrict`. The deprecated metrics module (`count_partition_single`, per-scrape counts) and
     `count_completed` are not targets.
  3. **P3 — retention / cleanup / admin** (`find_*_before`, `ids_*_before`, `delete_by_ids`, `delete_by_status_failed`,
     `delete_by_key_status`, `find_*_all`, `find_incomplete_by_key`): no index is required for them. They only need correct SQL
     (e.g. a SQL row limit is fine); slow is acceptable.
  - **Decision rule:** an index is justified only by P1/P2. An index that serves only P3, or whose read benefit does not outweigh its
    write cost on P1 writes (insert/update latency, WAL volume, index size, HOT/bloat), should be dropped. Dropping indexes to make
    the normal processing faster is an explicit goal. Experiments must therefore report write cost (latency + WAL bytes per write +
    index sizes) whenever DDL changes, and P3 regressions are reported but do not block a recommendation.
- 2026-11-01: **Preference: `ORDER BY` + row limit over `MIN` / `GROUP BY` when the performance is the same** (aggregates stay for counts). `findOldestDueRetry` stays `MIN` on PostgreSQL (E1M); re-check on the other dialects in phase 6.

## Stopped run (cross-dialect main+v1)

- Stopped by the user while MariaDB S2 `prepare` for v1 was running (MariaDB `prepare` of S2 took ~35 min; MySQL main retention queries ~285 s per execution).
- Completed, both variants: MySQL S1, S2, S8-S2; MariaDB S1, S2, S8-S2 (+ MariaDB main S2 writecost). Missing: MySQL S4, S8-S4, S5, S7, writecost, equivalence; MariaDB rest; SQL Server, Oracle, H2 entirely; per-dialect SUMMARY/compare files not written.
- Harness changes made during that run (see `harness/README.md` "Changes"): >30 s slow-query cap (1 execution, non-executing plan), `--groups` option, driver `bin/matrix-sql.sh` (`SKIPGEN=1`, finished results skipped unless `REDO=1`).

## Next step

Phase 5 done on PostgreSQL. Now: finish phases 3/4, then stop. Open for the user: UUIDv7 as a code change; whether COMPLETED in `record-statuses` is common (favours the full `(status, partition_no)` index).

**Finishing phases 3/4 (after phase 5):** main + v1 on the remaining non-PostgreSQL cells, serially: MySQL S4, S8-S4, S5, S7, writecost, equivalence; MariaDB the same; SQL Server, Oracle, H2 entirely; JPA on PostgreSQL if not measured; per-dialect summaries; `03-baseline.md`, `04-v1.md`. Driver `bin/matrix-sql.sh`. First make MariaDB/MySQL `prepare` create secondary indexes after the load (S2 prepare took ~35 min), and validate SQL Server on S1 (incl. its `keys_nonstrict` form). Phase 6 then only adds v2 on the same seeded data.
