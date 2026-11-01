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

`E1Q` reverts several items in one run because each item touches a disjoint set of query ids; report each item from its own query ids.
Make sure the override files keep the main `maxrows` header (main limits with `setMaxRows`, no `:limit`).

## Scenarios

For every variant: S2, S8-S2 (`--scenario S2 --owned mod4`), S4, S8-S4, S5, S6, S7. Warmup 5, reps 20, `--prepare` before each bench.
Order of variants per snapshot: rotate the starting variant per scenario (e.g. S2 starts with main, S4 with E02b, …) to avoid
systematic order bias; record the order.

Additionally on S2 (fresh prepare each): `writecost` for `main`, `v1`, `E01a`, `E02a`, `E02b`, `E11`, `E13`; and `concurrency`
(pollers 1, rate 0, 60 s; pollers 4, rate 0, 60 s) for `v1`, `E02b`, `E13`.

Equivalence (`bin/bench equiv`) against the base on S2 and S5 (and S7 for E02*): E01a/b/c vs main, E02a/b/c and E11–E14, E1Q vs v1.

## Noise

Use `docs/db-sql/STATE.md` thresholds: HOT/WRITE 30 %, MON/RET/ADMIN 20 %, and always > 0.15 ms absolute.

## Reports

`docs/db-sql/experiments/E01-status-created-main.md`, `E02-status-created-v1.md`, `E1x-v1-ablation-leave-one-out.md`
(one section per V1 item with a verdict: `matters` / `no difference` / `harmful`), each per brief 04: hypothesis, exact change,
results table (query × scenario: base, experiment, main, v1, relative change), plan excerpts for affected queries,
write cost and index sizes, verdict, portability, follow-ups. V1-10/V1-11 (metrics) need no run: compute from the results
(`count_statuses` vs `count_new + count_failed`, and the removed `count_completed` per refresh) and report them in the ablation file.
