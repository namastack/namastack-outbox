# Phase 5 — round 1 spec (PostgreSQL)

Written by the orchestrator. One measuring sub-agent runs all variants below on each snapshot (snapshots are generated once
per scenario and shared), then writes one report per experiment. All variants are SQL-level harness runs with DDL overrides
(`--ddl`) and/or query overrides (`--queries`); no code change, no new worktree.

Base DDL files: main = `/home/zamo/git/outbox-wt-main/namastack-outbox-jdbc/src/main/resources/schema/postgres/outbox-tables.sql`,
v1 = `/home/zamo/git/outbox-wt-v1/.../schema/postgres/outbox-tables.sql`. Put every derived DDL/queries dir under
`docs/db-sql/experiments/<Exx>-<slug>/` (`ddl.sql`, `queries/<id>.sql`).

## Variants

| Variant | Experiment | Base | DDL | Queries |
|---|---|---|---|---|
| `main` | reference | main | main | main catalog |
| `v1` | reference | v1 | v1 | v1 catalog |
| `E01a` | E01 | main | main + `CREATE INDEX idx_outbox_record_status_created_id ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key);` | main catalog |
| `E01b` | E01 | main | as E01a | main catalog, overridden by v1 catalog files for `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_strict` |
| `E01c` | E01 | main | main + `CREATE INDEX idx_outbox_record_status_created_id ON outbox_record (status, created_at, id);` (no INCLUDE) | as E01b |
| `E02a` | E02 | v1 | v1 − `idx_outbox_record_created_id_incomplete` + E01a index (non-partial, INCLUDE) | v1 catalog |
| `E02b` | E02 | v1 | v1 − `idx_outbox_record_created_id_incomplete` + `CREATE INDEX idx_outbox_record_status_created_id_incomplete ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL;` | v1 catalog |
| `E02c` | E02 | v1 | v1 + the E02b index (v1 queue index kept) | v1 catalog |
| `E11` | ablation −V1-1a | v1 | v1 + `CREATE INDEX idx_outbox_record_record_key_created ON outbox_record (record_key, created_at);` | v1 catalog |
| `E12` | ablation −V1-1b | v1 | v1 with `idx_outbox_record_record_key_completed_created_id` replaced by `(record_key, completed_at, created_at)` (no `id`) | v1 catalog |
| `E13` | ablation −V1-2 | v1 | v1 − `idx_outbox_record_created_id_incomplete` | v1 catalog |
| `E14` | ablation −V1-3 | v1 | v1 − `CREATE STATISTICS outbox_record_status_completed_stx` | v1 catalog |
| `E1Q` | ablation −V1-4..V1-9 (query items, one run) | v1 | v1 | v1 catalog, overridden by **main** catalog files for `keys_strict` (−V1-4), `keys_nonstrict` (−V1-5), `oldest_created` (−V1-6), `oldest_due_strict` (−V1-7), `pages` (−V1-8), `find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before` (−V1-9) |
| `E1P` | PG pages override needed? (user idea, 2026-11-01) | v1 | v1 | v1 catalog, overridden by `experiments/E1P-pages-default-pg/queries/pages.sql` = the base-class `findByKeysAndStatus` (`record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id`) instead of the PostgreSQL `unnest` + `CROSS JOIN LATERAL` form |

`E1Q` reverts several items in one run because each item touches a disjoint set of query ids; report each item from its own query ids.
Make sure the override files keep the main `maxrows` header (main limits with `setMaxRows`, no `:limit`).

## Scenarios

For every variant: S2, S8-S2 (`--scenario S2 --owned mod4`), S4, S8-S4, S5, S6, S7. Warmup 5, reps 20, `--prepare` before each bench.
Order of variants per snapshot: rotate the starting variant per scenario (e.g. S2 starts with main, S4 with E02b, …) to avoid
systematic order bias; record the order.

Additionally on S2 (fresh prepare each): `writecost` for `main`, `v1`, `E01a`, `E02a`, `E02b`, `E11`, `E13`; and `concurrency`
(pollers 1, rate 0, 60 s; pollers 4, rate 0, 60 s) for `v1`, `E02b`, `E13`.

Equivalence (`bin/bench equiv`) against the base on S2 and S5 (and S7 for E02*): E01a/b/c vs main, E02a/b/c and E11–E14, E1Q vs v1.

### E1P scenarios (pages only; `--only pages,keys_strict`)

Only `v1` and `E1P` are needed, back to back on each snapshot: S2, S2 `--batch 100 --label S2-b100`, S4, S4 `--batch 100`,
S4 `--batch 1000 --label S4-b1000` (1000 = `MAX_RECORD_KEYS_PER_FETCH`, one bulk load at its maximum key count), S5 (2 000 rows per key:
large pages), S7, S8-S2. Equivalence E1P vs v1 on S2, S4 `--batch 100`, S5 (rows and order per key must be identical).
Check in the plans whether PostgreSQL 18 serves the `IN` list with an ordered index scan on
`(record_key, completed_at, created_at, id)` (no Sort node), or sorts. Also note that the `IN` list is expanded to one placeholder
per key, so each key count is a different statement for the driver's statement cache (the `ARRAY` form is one statement).
Report: `experiments/E1P-pages-default-pg.md`, verdict whether the PostgreSQL override is necessary.

### E14 details (extended statistics on PostgreSQL)

Extended statistics change only planner estimates, so timings alone can hide the effect. For `v1` and `E14` additionally:
- Compare **estimated vs actual rows** (from the `.json` plans) for every node filtering on both `status` and `completed_at`:
  `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_strict`, `pages`, `count_statuses`. Report the misestimate factor
  with and without the statistics object, and whether the **plan shape** changes (join method, index choice, Sort, row goal).
- Scenarios: all round-1 scenarios plus **S9** (after churn; statistics come only from autoanalyze there) and S10 concurrency
  (pollers 4, rate 0) for `v1` vs `E14`.
- Generic plans: run `keys_strict` and `pages` once more with `SET plan_cache_mode = force_generic_plan` for both variants
  (estimates for `$1` parameters do not use the MCV list the same way); report the plan and timing.
- Cost: duration of `ANALYZE outbox_record` with and without the statistics object (S2).
Verdict per brief: keep only if a plan or a timing changes above the noise threshold in some scenario; otherwise `no difference`
(the object then only costs ANALYZE time and is a PostgreSQL-only DDL difference). The add-one counterpart (main + statistics) is
round 2 (E2x) and is only needed if E14 shows an effect.

## Query priorities (user decision, see STATE.md "User decisions")

Rank every result by priority: **P1** hot path (`keys_*`, `pages`, `insert`, `upd_*`, `delete_by_id`, S10), **P2** new metrics
(`count_statuses`, `count_partitions`, `oldest_*`), **P3** retention/cleanup/admin (no index needed, regressions reported only).
Verdicts are decided by P1 first, then P2. For every DDL variant, report write cost: committed insert and `upd_complete` latency,
**WAL bytes per write** (`pg_current_wal_lsn()` difference over the `writecost` run divided by the number of writes; add this to
`writecost` if missing and document it in harness/README.md "Changes"), index count and sizes. An index that only helps P3 is a
candidate for removal, not a win.

## Noise

Use `docs/db-sql/STATE.md` thresholds: HOT/WRITE 30 %, MON/RET/ADMIN 20 %, and always > 0.15 ms absolute.

## Reports

`docs/db-sql/experiments/E01-status-created-main.md`, `E02-status-created-v1.md`, `E1x-v1-ablation-leave-one-out.md`
(one section per V1 item with a verdict: `matters` / `no difference` / `harmful`), each per brief 04: hypothesis, exact change,
results table (query × scenario: base, experiment, main, v1, relative change), plan excerpts for affected queries,
write cost and index sizes, verdict, portability, follow-ups. V1-10/V1-11 (metrics) need no run: compute from the results
(`count_statuses` vs `count_new + count_failed`, and the removed `count_completed` per refresh) and report them in the ablation file.
