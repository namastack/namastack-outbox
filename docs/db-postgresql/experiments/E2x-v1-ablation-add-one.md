# E2x - v1 add-one ablation on main (PostgreSQL 18.6, round 2)

Experiments E21, E22, E22b, E23, E24, E26, E28 (spec: `docs/db-postgresql/experiments/round2-spec.md`). Base = `main`; each variant adds (or, for E21,
removes) exactly one v1 item. Counterpart of the round 1 leave-one-out report `E1x-v1-ablation-leave-one-out.md`.

Paths are relative to `docs/db-postgresql/results/`. "T" = `<variant>/postgres/<label>-R2/timings.csv` (median ms, 20 reps, 5 warmup; reps are fewer for
cells slower than 2 s), "PC" = `.../plancache.csv`, "P" = `.../plans/<query>.txt`, "W" = `<variant>/postgres/S2-R2/writecost.csv`.
Every number below is one run of one 20-rep sample unless it says otherwise (no repeat of any E2x variant was measured).

## Setup and rules

- Variants and their change: `E21` drop `idx_outbox_record_record_key_created` (V1-1a); `E22` add v1's partial `idx_outbox_record_created_id_incomplete`
  (V1-2, main SQL); `E22b` add the E02b index `(status, created_at, id) INCLUDE (...) WHERE completed_at IS NULL` (main SQL); `E23` add
  `CREATE STATISTICS ... (mcv) ON status, completed_at` (V1-3); `E24` v1 `keys_strict` + `keys_nonstrict` (V1-4, V1-5), no new index; `E26` v1
  `oldest_created` + `oldest_due_strict` (V1-6, V1-7); `E28` v1 `pages` (V1-8). DDL and query files: `experiments/R2-<variant>/`.
  E24/E26/E28 ran with `--only` on their overridden ids plus `keys_strict` as a control.
- Labels: **S8-S2, S8-S4 = partial partition ownership (`--owned mod4`, the default deployment, ranked first)**; S2, S4 also run with full ownership; **S5, S6, S7 are
  full ownership only** (no S8 counterpart was measured), so they say nothing about the default deployment.
- Priorities: P1 = `keys_*`, `pages`, `insert`, `upd_*`, `delete_by_id`; P2 = `count_statuses`, `count_partitions`, `oldest_*`; P3 reported only.
  `count_statuses` is not in the main catalog, so it has no main reference here.
- Noise (STATE.md): a difference counts only if it exceeds the group threshold (HOT/WRITE 30 %, MON/RET/ADMIN 20 %) AND 0.15 ms.
- Equivalence vs main on S2, S5, S7: all seven variants 24 PASS, 0 FAIL, 0 ERROR, 2 SKIP (`find_pending_all`, `find_failed_all`, unbounded)
  (`equiv/postgres/<S2|S5|S7>/<variant>-vs-main.md`).
- **Plan-mode caveat (see `R2-plan-modes.md`).** `PC` shows whether the timed reps ran custom or generic plans. Where two compared cells differ in plan
  mode, the difference is not attributable to the variant and is marked "mode flip" below. This affects `keys_nonstrict` S4 (main and E22 ran
  4 custom + 16 generic reps, E21/E22b/E23 ran 20 custom reps: `main|E22|E21|E22b|E23/postgres/S4-R2/plancache.csv`, line `keys_nonstrict`).
- Variant order was rotated per label (`logs/round2-pg.log`): S2 main first; S8-S2 main near last; S8-S4 main 9th of 17; S4 main 12th; S5 6th; S6 3rd; S7 last.

## Verdict per v1 item (add-one on main)

| v1 item | variant | verdict | one-line reason |
|---|---|---|---|
| V1-1a drop `(record_key, created_at)` | E21 | **matters alone** (space and WAL only) | no read change in any label; -67 MB index bytes, about -3 % WAL per write |
| V1-2 partial queue index | E22, E22b | **matters only with another item** (V1-4/V1-5) | alone: P1 wins on S8-S4 and S6 but losses on S8-S2 and S7; the large effect needs the v1 key SQL |
| V1-3 `CREATE STATISTICS` | E23 | **no difference** alone on main SQL | no cell beyond noise that is not a plan-mode flip; round 1 E14 shows it matters with the v1 key SQL and custom plans |
| V1-4 / V1-5 key SQL | E24 | **matters only with another item** (V1-2) | alone: `keys_strict` -81 % to -99 % on S4-S7 and S8-S4, but `keys_nonstrict` +84 % (S8-S2) and +210 % (S2); with the queue index both are about 1 ms |
| V1-6 `oldest_created` ORDER BY | E26 | **matters only with another item** (V1-2) | alone it is slower (S8-S4 +118 %); `oldest_due_strict` (V1-7) **matters alone** (-99 % on S4-S6) |
| V1-8 pages bulk load | E28 | **no difference** on partial ownership | S8-S2 -4 %, S8-S4 +7 %; matters on S7 (-57 %) and possibly S5, full ownership only |

## Partial ownership first (S8-S2, S8-S4)

Median ms, main vs variant. "n.s." = within noise. Sources: `T main|E2x/postgres/S8-S2-R2`, `.../S8-S4-R2`.

| query (prio) | label | main | E21 | E22 | E22b | E23 | E24 | E26 | E28 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| keys_strict (P1) | S8-S2 | 2.182 | 2.113 | **3.409 (+56 %)** | **3.562 (+63 %)** | 2.023 | 1.793 (n.s.) | 2.340 | 2.291 |
| keys_strict (P1) | S8-S4 | 2870 | 2863 | **1982 (-31 %)** | **1987 (-31 %)** | 2871 | **528 (-82 %)** | 2854 | 2858 |
| keys_nonstrict (P1) | S8-S2 | 0.991 | 0.857 | 0.926 | 1.072 | 0.917 | **1.820 (+84 %)** | - | - |
| keys_nonstrict (P1) | S8-S4 | 1451 | 1446 | 1447 | 1447 | 1443 | **530 (-63 %, mode differs)** | - | - |
| pages (P1) | S8-S2 | 0.937 | 0.858 | 1.007 | 0.979 | 0.874 | - | - | 0.900 (n.s.) |
| pages (P1) | S8-S4 | 0.706 | 0.771 | 0.720 | 0.737 | 0.688 | - | - | 0.757 (n.s.) |
| oldest_created (P2) | S8-S2 | 0.712 | 0.738 | 0.718 | 0.746 | 0.761 | - | **0.893 (+25 %)** | - |
| oldest_created (P2) | S8-S4 | 211.0 | 211.6 | 211.9 | 211.4 | 211.0 | - | **459 (+118 %)** | - |
| oldest_due_strict (P2) | S8-S2 | 1.547 | 1.534 | **2.56 (+65 %)** | **2.57 (+66 %)** | 2.38 | - | **0.973 (-37 %)** | - |
| oldest_due_strict (P2) | S8-S4 | 852 | 849 | **436 (-49 %)** | **445 (-48 %)** | 848 | - | **0.847 (-100 %)** | - |
| count_partitions (P2) | S8-S2 / S8-S4 | 0.497 / 16.4 | 0.653 / 16.1 | 0.667 / 16.3 | 0.506 / 15.9 | 0.672 / 16.2 | - | - | - |
| oldest_due_nonstrict (P2) | S8-S2 / S8-S4 | 0.246 / 0.293 | 0.286 / 0.272 | 0.213 / 0.261 | 0.261 / 0.310 | 0.288 / 0.266 | - | - | - |
| insert, upd_complete, upd_retry, upd_fail, delete_by_id (P1) | S8-S2 | 0.411, 0.375, 0.299, 0.257, 0.154 | all within noise | within noise | within noise | within noise | - | - | - |

Notes:

- `count_partitions` S8-S2 main 0.497 vs E21 0.653 / E23 0.672 is +0.16 ms and +31 % but the same difference appears in E22 (+0.17): run-to-run, not a variant effect (E21 and E23 touch no index it uses).
- S8-S4 `keys_nonstrict` is a plan-mode confound: main, E21, E22, E22b, E23 all ran 4 custom + 16 generic reps (`PC` S8-S4, line `keys_nonstrict`), E24 ran 20 custom reps. The -63 % is SQL plus mode.
- `oldest_due_strict` S8-S4 main 852 ms: main's SQL scans all NEW rows; E26's `ORDER BY next_retry_at LIMIT 1` stops after one row through `idx_outbox_record_status_retry`. `oldest_created` S8-S4 E26 459 ms is a parallel Sort of all NEW rows (plan below).
- S8-S2 and S8-S4 were measured on one snapshot each with 64 partitions owned of 256 (`mod4`); the E-variant set was rotated, main was 15th and 9th.

## Full ownership (S2, S4 also; S5, S6, S7 full ownership only)

Median ms, main vs variant (`T <variant>/postgres/<label>-R2`). Only cells beyond noise are marked.

| query (prio) | label | main | E21 | E22 | E22b | E23 | E24 | E26 | E28 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| keys_strict (P1) | S2 | 5.73 | 5.51 | 4.24 (-26 %, n.s.) | 3.93 (-31 %) | 5.42 | 4.21 (-27 %, n.s.) | 5.43 | 5.61 |
| keys_strict (P1) | S4 | 4272 | 4388 | 4169 | 4089 | 4354 | **807 (-81 %)** | 4299 | 4332 |
| keys_strict (P1) | S5 | 14499 | 14643 | 14594 | 15005 | 15224 | **145 (-99 %)** | 14651 | 14637 |
| keys_strict (P1) | S6 | 475 | 414 | **177 (-63 %)** | **195 (-59 %)** | 416 | **109 (-77 %)** | 477 | 412 |
| keys_strict (P1) | S7 | 55.4 | 55.2 | **74.7 (+35 %)** | **74.7 (+35 %)** | 55.8 | **8.52 (-85 %)** | 55.1 | 55.3 |
| keys_nonstrict (P1) | S2 | 1.35 | 1.51 | 1.64 | 1.45 | 1.53 | **4.19 (+210 %)** | - | - |
| keys_nonstrict (P1) | S4 | 5860 | 1369 (mode flip) | 5982 | 1366 (mode flip) | 1379 (mode flip) | **804 (-86 %, mode differs)** | - | - |
| keys_nonstrict (P1) | S5 / S6 / S7 | 117 / 109 / 8.74 | 119 / 110 / 8.76 | 118 / 114 / 8.60 | 117 / 110 / 8.46 | 123 / 110 / 8.66 | 144 (+23 %) / 108 / 8.09 | - | - |
| pages (P1) | S5 | 200.8 | 146.6 (-27 %) | 150.1 (-25 %) | 145.5 (-28 %) | 189.1 | - | - | **134.8 (-33 %)** |
| pages (P1) | S7 | 1.606 | 1.631 | 1.618 | 1.500 | 1.631 | - | - | **0.697 (-57 %)** |
| pages (P1) | S2 / S4 / S6 | 0.783 / 0.783 / 0.986 | within noise | within noise | within noise | within noise | - | - | 0.664 / 0.796 / 0.810 (n.s.) |
| oldest_created (P2) | S4 / S5 / S6 | 215 / 63.4 / 85.1 | n.s. | n.s. | n.s. | n.s. | - | **476 (+121 %) / 95.2 (+50 %) / 116 (+37 %)** | - |
| oldest_due_strict (P2) | S2 | 3.83 | 3.80 | 3.21 | 3.11 | 3.89 | - | **1.14 (-70 %)** | - |
| oldest_due_strict (P2) | S4 / S5 / S6 / S7 | 691 / 4950 / 169 / 34.9 | n.s. | S6 **87.7 (-48 %)**, S7 **58.5 (+67 %)** | S6 **89.9 (-47 %)**, S7 **59.4 (+70 %)** | n.s. | - | **1.10 / 1.17 / 1.06 / 1.19 (-97 % to -100 %)** | - |

`pages` S5 -25 % to -28 % in E21, E22, E22b (no pages change) and -33 % in E28 are all about the same size: the S5 `pages` cell moves by -25 % to -33 % for any
variant (`main` 200.8 in the 6th run position of S5; v1b 94.7). It is below the 30 % HOT threshold for E21/E22/E22b and only just above it for E28, so the E28 S5 gain is not distinguishable from this spread (E1P report says the same: parallel-plan variance).

## Verdicts in detail

### V1-1a, E21: drop `idx_outbox_record_record_key_created` - matters alone (space and WAL), no read effect

- Reads: no P1/P2 cell beyond noise in S8-S2, S8-S4, S2, S5, S6, S7 (tables above). The only flagged P1 cell is S4 `keys_nonstrict` -77 % (5860 to 1369 ms), which is a plan-mode flip:
  main ran 4 custom + 16 generic, E21 ran 20 custom (`main|E21/postgres/S4-R2/plancache.csv`), and E22b and E23 show the same -76/-77 % without touching that index. Not attributable to V1-1a.
- Write cost (S2, one run, `<variant>/postgres/S2-R2/writecost.csv`): index count 6 -> 5; `indexes_total_bytes_before` 309,043,200 -> 241,754,112 (-67.3 MB, -21.8 %);
  `insert_1thread.wal_bytes_per_write` 6576.6 -> 6369.2 (-3.2 %); `upd_complete_committed.wal_bytes_per_write` 7590.2 -> 7340.3 (-3.3 %); insert p50 1.917 -> 1.824 ms (-4.9 %), upd_complete p50 1.926 -> 1.952 ms.
  WAL repeat noise from the repeated writecost runs of v1 and v1b: 1.2 % and 0.5 % (`v1|v1b/postgres/S2-R2|S2-wc2-R2/writecost.csv`, `insert_1thread.wal_bytes_per_write`), so -3 % is above that noise but is a single run. Latency noise in the same pairs 0-5 % (MIN 21 %), so the latency deltas are noise.
- P3: `find_incomplete_by_key` (a query that used the dropped index) S2 0.152 -> 0.124 ms, S8-S2 0.157 -> 0.141 ms: no change beyond noise (`main|E21/postgres/S2-R2|S8-S2-R2/timings.csv`); `find_completed_before` S8-S4 +21 % (1571 -> 1896 ms) is above threshold but the same +22 % appears in E22 and is a plan-mode/noise cell (see `R2-plan-modes.md`).
- Round 1 E11 (re-adding the index to v1) reached the same conclusion from the other side.

### V1-2, E22 (v1 index on main SQL) and E22b (E02b index on main SQL): matters only with another item

- E22 and E22b behave alike (the two indexes have the same predicate and nearly the same size, 1.9 and 2.0 MB; `E22|E22b/postgres/S2-R2/writecost.csv`).
- Wins: `keys_strict` S8-S4 -31 % (2870 -> 1982 / 1987 ms), S6 -63 % / -59 %, S2 -31 % (E22b, 3.93 ms; E22 -26 % is below threshold); `oldest_due_strict` S8-S4 -49 / -48 %, S6 -48 / -47 %.
- Losses (P1 and P2, same index, both variants): `keys_strict` **S8-S2 +56 % / +63 %** (2.18 -> 3.41 / 3.56 ms), **S7 +35 %** (55.4 -> 74.7 ms), `oldest_due_strict` **S8-S2 +65 % / +66 %** (1.55 -> 2.56 ms), **S7 +67 % / +70 %** (34.9 -> 58.5 / 59.4 ms).
  Plan on S7 (`E22/postgres/S7-R2/plans/keys_strict.txt` vs `main/postgres/S7-R2/plans/keys_strict.txt`): main joins through the record-key index with a parallel bitmap scan and a nested-loop anti join (cost 49866); with the new index the planner switches to a `Hash Right Anti Join` that scans 400,000 index entries of `idx_outbox_record_created_id_incomplete` (cost 59199, 93.5 ms). The index changes the plan to a worse one for main's SQL.
- The big S4/S5 effect is absent: S4 `keys_strict` 4272 -> 4169 / 4089 ms (-2 % / -4 %), S5 14499 -> 14594 / 15005 ms (+1 % / +3 %), also with partial ownership (S8-S4 -31 %, still 2 s).
  The index alone does not remove main's per-key `GROUP BY` / `MIN` aggregation over all NEW rows.
- Interaction with the SQL (S4 `keys_strict`): main SQL + no new index 4272 ms (`main`), main SQL + queue index 4089 ms (`E22b`), v1 SQL without the queue index 807 ms (`E24`), v1 SQL + queue index 1.25 ms (`v1b`, `v1b/postgres/S4-R2/timings.csv`). Each item alone leaves the cell at 0.8 s to 4.3 s; both together reach about 1 ms.
- Write cost (S2 one run, `<variant>/postgres/S2-R2/writecost.csv`): index count 6 -> 7; `indexes_total_bytes_before` 309.0 -> 311.0 MB (+1.9 MB, +0.6 %);
  `insert_1thread.wal_bytes_per_write` 6576.6 -> E22 6884.9 (+4.7 %), E22b 6805.9 (+3.5 %); `insert_4threads.wal_bytes_per_write` 6548.7 -> 6944.1 (+6.0 %) / 6908.2 (+5.5 %);
  `upd_complete_committed.wal_bytes_per_write` 7590.2 -> 7591.0 / 7586.3 (no change). WAL noise 0.5-1.2 % (v1, v1b pairs; MIN 4 threads 15 %), so the insert WAL cost of about +300 B per insert is real but small.
  Latency: E22 insert p50 1.291 ms and upd_complete p50 1.316 ms are 33 % below main (an added index cannot make writes faster): single-run noise, not a gain; E22b 1.843 / 2.122 ms (-4 % / +10 %), no difference.
- P3: `find_incomplete_by_key` E22 S2 / S8-S2 +128 % / +120 % (0.152 -> 0.346 ms) and E23 S8-S2 +127 % (0.157 -> 0.356 ms) while E23 S2 is 0.160: the slow cells are exactly those with 20 custom reps, the fast ones 4 custom + 16 generic (`E22|E23|main/postgres/S2-R2|S8-S2-R2/plancache.csv`), so the cell is bimodal by plan mode, not an index effect.

### V1-3, E23: `CREATE STATISTICS` on main - no difference alone

- No P1/P2 cell beyond noise in S8-S2, S8-S4, S2, S5, S6, S7. S4 `keys_nonstrict` -76 % is the same plan-mode flip as E21/E22b (`E23/postgres/S4-R2/plancache.csv`: 20 custom reps).
- Reason (not a measurement of E23 itself): main's key SQL does not contain the `status = 'NEW' AND completed_at IS NULL` pair the statistics object describes. Round 1 E14 showed the object matters only with the v1 SQL and the queue index and custom plans (S2 `keys_strict` 2.14 -> 4.32 ms without it, `E1x-v1-ablation-leave-one-out.md`). Whether it matters in production is analysed in `R2-plan-modes.md`.
- Write cost: no row-level cost expected; `insert_1thread.wal_bytes_per_write` 6488 (-1.4 %), `upd_complete_committed` 7601.6 (+0.2 %), indexes unchanged (`E23/postgres/S2-R2/writecost.csv`).
- ANALYZE cost is not measured in R2 (round 1: +8.3 % ANALYZE time, E14 report).

### V1-4 / V1-5, E24: v1 key SQL on main DDL - matters only with the queue index

- `keys_strict` improves in every label with a big NEW backlog: S4 -81 % (4272 -> 807 ms), S5 -99 % (14499 -> 145 ms), S6 -77 % (475 -> 109 ms), S7 -85 % (55.4 -> 8.52 ms), S8-S4 -82 % (2870 -> 528 ms). S2 -27 % and S8-S2 -18 % are below threshold.
- `keys_nonstrict` **regresses** where the backlog is small: S2 1.35 -> 4.19 ms (+210 %), S8-S2 0.991 -> 1.820 ms (+84 %); S5 +23 % is below threshold. Plan S2 (`E24/postgres/S2-R2/plans/keys_nonstrict.txt`): `Nested Loop Anti Join` with an index probe per NEW row (1000 loops) plus a `Sort` on `(created_at, id)` of all 1000 NEW rows, versus main's single `HashAggregate` over the status index (`main/postgres/S2-R2/plans/keys_nonstrict.txt`, 1.0 ms).
  The v1 SQL needs the queue index to avoid sorting all NEW rows: v1b has 1.09 ms (S2), 1.0 ms (S8-S4) with the same SQL (`v1b/postgres/S2-R2/timings.csv`, `S8-S4-R2`).
- S4 / S8-S4 `keys_*` with the v1 SQL but without the queue index still sort all NEW rows (`E24/postgres/S4-R2/plans/keys_strict.txt`: `Gather Merge > Sort > Parallel Index Scan idx_outbox_record_status`, 333,333 rows per worker), hence 0.8 s / 0.5 s versus about 1 ms with it (v1b).
- Comparison cell S4 `keys_nonstrict` -86 % (5860 -> 804 ms) mixes SQL and plan mode (main 4 custom + 16 generic, E24 20 custom): cite it only with that caveat.
- Answer to "does the key SQL need the queue index?": yes for P1. Without it, `keys_strict` is a sort of the whole NEW set (0.1-0.8 s on S4-S6) and `keys_nonstrict` loses 1.3-3 ms on small backlogs; with it (v1b) both are about 1 ms on every label.
- No DDL change, so no write cost.

### V1-6 / V1-7, E26: `oldest_created` and `oldest_due_strict` SQL

- `oldest_due_strict` (V1-7): **matters alone**. S8-S4 852 -> 0.847 ms, S4 691 -> 1.10, S5 4950 -> 1.17, S6 169 -> 1.06, S7 34.9 -> 1.19, S2 3.83 -> 1.14, S8-S2 1.55 -> 0.973 (-37 %). It uses `idx_outbox_record_status_retry` and stops after one row; no new index needed. Same answer as round 1 E1Q.
- `oldest_created` (V1-6): **worse alone**: S8-S4 211 -> 459 ms (+118 %), S4 215 -> 476 (+121 %), S5 63.4 -> 95.2 (+50 %), S6 85.1 -> 116 (+37 %), S8-S2 0.712 -> 0.893 (+25 %). Plan S4 (`E26/postgres/S4-R2/plans/oldest_created.txt`): `Limit > Gather Merge > Sort(created_at) > Parallel Index Scan idx_outbox_record_status` over 333,333 NEW rows per worker, i.e. `ORDER BY created_at LIMIT 1` without a `created_at`-ordered index sorts the NEW set; main's `MIN` aggregate on the same scan is cheaper (`main/postgres/S4-R2/plans/oldest_created.txt`, 231 ms).
  With the queue index (v1b) the cell is 0.41 ms (S8-S4) to 0.75 ms (S4): the item **matters only with V1-2**.
- Both queries are P2 (observability collector); P1 `keys_strict` control unchanged in E26 (S8-S2 +7 %, S4 +1 %).

### V1-8, E28: v1 `pages` (`unnest` + `LATERAL`) on main DDL - no difference on partial ownership

- Partial ownership: S8-S2 0.937 -> 0.900 ms (-4 %), S8-S4 0.706 -> 0.757 ms (+7 %): no difference. Full ownership S2 -15 %, S4 +2 %, S6 -18 %: no difference.
- S7 0.697 vs 1.606 ms (-57 %) is a real plan effect: main's `IN (...)` form combines a bitmap on the record-key index with a bitmap on `idx_outbox_record_status` for `NEW` (10,000 rows, 0.76 ms), the `LATERAL` form probes `idx_outbox_record_record_key_completed_created` per key (0.122 ms) (`main|E28/postgres/S7-R2/plans/pages.txt`). Full ownership only; main's bitmap-AND plan appears only with the S7 backlog (10,000 NEW rows).
- S5 -33 % (200.8 -> 134.8 ms) is not distinguishable from the -25 % to -28 % that E21/E22/E22b show with an unchanged `pages` (see note above).
- Round 1 E1P: the base `IN` form performs the same on v1; v1b uses it. Net: V1-8 is not needed.

## Interactions summary

| combination | S4 keys_strict | S8-S4 keys_strict | S2 keys_nonstrict | oldest_created S4 | source |
|---|---:|---:|---:|---:|---|
| main | 4272 | 2870 | 1.35 | 215 | `main/postgres/<S4|S8-S4|S2>-R2` |
| + queue index only (E22b) | 4089 | 1987 | 1.45 | 214 | `E22b/...` |
| + v1 key SQL only (E24) | 807 | 528 | 4.19 | - | `E24/...` |
| + v1 `oldest_created` SQL only (E26) | - | - | - | 476 | `E26/...` |
| v1b (all of them) | 1.25 | 0.931 | 1.09 | 0.752 | `v1b/...` |

## Write cost table (S2, one run each, `<variant>/postgres/S2-R2/writecost.csv`)

| variant | indexes | index bytes before | insert p50 (1 thr) | insert WAL B/write | upd_complete p50 | upd_complete WAL B/write |
|---|---:|---:|---:|---:|---:|---:|
| main | 6 | 309.0 MB | 1.917 ms | 6577 | 1.926 ms | 7590 |
| E21 | 5 | 241.8 MB (-21.8 %) | 1.824 ms | 6369 (-3.2 %) | 1.952 ms | 7340 (-3.3 %) |
| E22 | 7 | 311.0 MB (+0.6 %) | 1.291 ms (outlier) | 6885 (+4.7 %) | 1.316 ms (outlier) | 7591 (0 %) |
| E22b | 7 | 311.0 MB (+0.6 %) | 1.843 ms | 6806 (+3.5 %) | 2.122 ms | 7586 (0 %) |
| E23 | 6 | 309.0 MB | 1.863 ms | 6488 (-1.4 %) | 2.050 ms | 7602 (+0.2 %) |
| noise reference v1 / v1 repeat | 6 | 289.9 MB | 2.015 / 1.911 ms | 6643 / 6722 (1.2 %) | 2.158 / 2.035 ms | 7520 / 7515 |
| noise reference v1b / repeat | 6 | 289.9 MB | 2.059 / 2.044 ms | 6731 / 6696 (0.5 %) | 2.136 / 2.129 ms | 7552 / 7507 |

(`v1|v1b/postgres/S2-R2/writecost.csv` and `S2-wc2-R2/writecost.csv`.)

## Caveats

- One run per cell and variant. Only the writecost of v1, v1b and MIN was repeated; nothing was repeated for E21-E28.
- The plan mode (custom vs generic) differs between variants in some cells (`PC`); such cells are flagged and not attributed. `R2-plan-modes.md` lists which cells.
- S5, S6, S7 are full ownership. For partial ownership (default deployment) only S8-S2 and S8-S4 exist; the S7 findings (E22 regression, E28 win) are not confirmed for it.
- SQL-level medians use rolled-back writes; S4 write cells vary by 2x between variants with identical plans (`R2-plan-modes.md`), so P1 write latency is judged on S8-S2 and `writecost` only, where nothing exceeds noise.
- PostgreSQL only; the v1 key SQL on other dialects is not covered.

## Follow-up ideas

- Re-run E24 + queue index only on S8-S2/S8-S4/S8-S7 (no S8-S7 label exists) to see whether the E22 S7 regression exists under partial ownership.
- Check whether `keys_nonstrict` could keep main's `HashAggregate` form on small backlogs (the E24 S2 regression) while using the v1 form on large ones; v1b with the queue index already avoids it.
