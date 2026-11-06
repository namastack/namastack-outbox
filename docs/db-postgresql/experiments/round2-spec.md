# Phase 5 — round 2 spec (PostgreSQL)

Written by the orchestrator after round 1 (reports: `E01-status-created-main.md`, `E02-status-created-v1.md`,
`E1x-v1-ablation-leave-one-out.md`, `E1P-pages-default-pg.md`). Same mechanics as round 1 (`round1-spec.md`): one gen per
scenario, all variants back to back on the shared snapshot, DDL (`--ddl`) and query (`--queries`, overlay on the catalog given by
`--catalog-variant`) overrides only, no code change, no new worktree. Result label suffix `-R2`. Warmup 5, reps 20, `--prepare`
before each bench, ordered parameter sampling, slow-query cap active, rotated variant order per label (logged).

Goals: (1) fix a new reference **v1b** from the round 1 verdicts; (2) find the **minimal index set** for P1/P2 (user rule: an index
is justified only by P1/P2 and only if its read benefit outweighs its write cost); (3) explain the bimodal cells (generic vs custom
plans); (4) add-one counterparts (E2x) for the v1 items that matter.

## New reference v1b

v1b = v1 with
- `idx_outbox_record_created_id_incomplete` replaced by the E02b index
  `CREATE INDEX idx_outbox_record_status_created_id_incomplete ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL;`
- `pages` = base `IN (...)` form (`experiments/E1P-pages-default-pg/queries/pages.sql`),
- `oldest_due_nonstrict` = v1 `MIN` (unchanged).

Directory `experiments/R2-v1b/` (`ddl.sql`, `queries/pages.sql`). All `R2-*` variants below start from v1b (catalog v1 + v1b
overrides + their own).

## Variants

| Variant | Base | DDL change vs base | Query overrides vs base | Question |
|---|---|---|---|---|
| `main` | — | — | — | reference |
| `v1` | — | — | — | reference |
| `v1b` | v1 | see above | `pages` | new reference |
| `A1` (E05-A1) | v1b | − `idx_outbox_record_status` | — | pure write overhead? (`(status)` is a prefix of `(status, next_retry_at)`) |
| `A2` (E05-A2) | v1b | − `idx_outbox_record_status_retry` | — | serves only P3? |
| `A5` (E05-A5) | v1b | − `idx_outbox_record_partition_status_retry` | — | `count_partitions` / `oldest_due_*` via the partial queue index? |
| `A6` (E05-A6 + E03 SQL) | v1b | − all three: `idx_status`, `idx_status_retry`, `idx_partition_status_retry` | add the literal `AND completed_at IS NULL` to `count_new`, `count_failed`, `count_statuses` (NEW/FAILED form only), `count_partitions`, `count_partition_single`, `oldest_due_nonstrict`, `find_failed_before`, `ids_failed_before` (invariant `completed_at IS NULL <=> status IN (NEW, FAILED)`) | can the single partial queue index serve P1 + P2? |
| `K12` (−V1-1b on v1b) | v1b | record-key index `(record_key, completed_at, created_at)` (no `id`) | — | `id` column worth 46 MB? |
| `E04` | v1b | record-key index replaced by `CREATE INDEX idx_outbox_record_key_created_id_incomplete ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;` | `pages`: `... AND completed_at IS NULL ORDER BY record_key, created_at, id`; `find_incomplete_by_key`: add `AND completed_at IS NULL` | partial record-key index: write win, P1 reads unchanged? |
| `MIN` | v1b | A6 DDL + E04 DDL (indexes: PK, partial queue index, partial record-key index) | A6 + E04 overrides | minimal index set = v2 candidate |
| `E21` | main | − `idx_outbox_record_record_key_created` (V1-1a) | — | add-one V1-1a |
| `E22` | main | + v1's `idx_outbox_record_created_id_incomplete` (V1-2) | — | add-one V1-2 (main SQL) |
| `E22b` | main | + E02b partial status-leading index | — | add-one of the round 1 winner index on main SQL |
| `E23` | main | + `CREATE STATISTICS ... (mcv) ON status, completed_at` (V1-3) | — | add-one V1-3 |
| `E24` | main | — | v1 `keys_strict` + `keys_nonstrict` (V1-4, V1-5) | add-one key SQL without new index |
| `E26` | main | — | v1 `oldest_created`, `oldest_due_strict` (V1-6, V1-7) | add-one oldest SQL |
| `E28` | main | — | v1 `pages` (V1-8) | add-one pages SQL |

E24/E26/E28 are SQL-only: run with `--only` restricted to their overridden ids plus `keys_strict` as a control.
Overrides that change SQL must keep the header format; main-based overrides keep main's `maxrows` header.

## Scenarios

All variants: S2, S8-S2, S4, S8-S4, S5, S6, S7. Equivalence vs base on S2, S5, S7 (R2 v1b-based variants vs **v1** — results must
equal v1; E2x vs main).

Write cost on S2 (`writecost`, incl. WAL bytes/write and index sizes) for every DDL variant: main, v1, v1b, A1, A2, A5, A6, K12, E04,
MIN, E21, E22, E22b, E23; repeat `writecost` a second time (fresh prepare) for v1, v1b and MIN to get a WAL/latency noise estimate.

S9 churn (`--inserts 500000`) for v1, v1b, A6, E04, MIN. S10 concurrency (pollers 1 and 4, rate 0, 60 s) for v1, v1b, A6, E04, MIN,
**twice each** (second pass in reverse variant order) to get a throughput noise estimate.

## Plan-mode study (bimodal cells)

Round 1 showed cells flipping between ~0.2–0.8 ms and ~22 ms on identical code paths (`count_failed`, `count_new`, S4 write cells),
and E1M showed a stable 0.2–0.5 ms gap with identical EXPLAIN plans. Hypothesis: pgjdbc switches to server-prepared statements after
5 executions and PostgreSQL then picks a generic plan (`status = $1`) for some runs.
For `v1` and `v1b` on S2, S4, S7: bench all queries three times — default, `--pg-plan-cache-mode force_custom_plan`,
`--pg-plan-cache-mode force_generic_plan` (labels `<S>-custom-R2`, `<S>-generic-R2`). Also capture, for the default mode, which
plan was used in the timed reps if the harness can (e.g. `auto_explain` with `log_min_duration=0` for one extra rep, or
`EXPLAIN (GENERIC_PLAN)` on PG 16+). Report which query × scenario cells are bimodal and why.

## Reports

- `experiments/R2-v1b.md` — v1b vs v1 vs main (confirms round 1 under one reference).
- `experiments/E05-drop-indexes.md` — A1, A2, A5, A6, K12 (per index: which queries use it, read loss, write gain).
- `experiments/E04-partial-record-key.md` — E04 and MIN (v2 candidate) with S9/S10.
- `experiments/E2x-v1-ablation-add-one.md` — E21–E28.
- `experiments/R2-plan-modes.md` — plan-mode study.

Verdicts by P1, then P2 (P3 reported only), noise thresholds from `STATE.md`; S10/writecost noise from the repeated runs.
Every DDL variant reports write cost (insert/upd_complete latency, WAL bytes/write, index count and sizes).
