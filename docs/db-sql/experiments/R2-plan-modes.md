# R2 plan-mode study: which cells are bimodal and why (PostgreSQL 18.6, round 2)

Hypothesis (`round2-spec.md`, "Plan-mode study"): pgjdbc server-prepares a statement after 5 executions and PostgreSQL then adopts a generic plan
(`status = $1`) for some runs, which produces cells that flip between ~0.2-0.8 ms and ~22 ms (`count_failed`, `count_new`, S4 write cells) and the
stable 0.2-0.5 ms E1M gap with identical EXPLAIN plans.

Paths are relative to `docs/db-sql/results/`. "T" = `<variant>/postgres/<label>-R2/timings.csv` (median ms), "PC" = `.../plancache.csv`
(`custom_timed`, `generic_timed` = plan counters of the server-prepared statement over the timed reps; `c20/g0` = 20 custom plans, `c4/g16` = 4 custom then
16 generic, `c0/g20` = 20 generic), "P" = `.../plans/<query>.txt`. Plan-mode labels: `<S>-custom-R2` (`force_custom_plan`) and `<S>-generic-R2`
(`force_generic_plan`), for v1 and v1b on S2, S4, S7 (24 label runs, e.g. `v1/postgres/S4-generic-R2/`). Default-mode cells are the `-R2` labels of all 17 variants.

## Summary

| Question | Answer |
|---|---|
| Do the ~22 ms cells (`count_failed`, `count_new`, `count_statuses`) come from generic plans? | **Yes, confirmed.** Forced generic gives 21.5-22.0 ms for these on S2, S4, S7 (v1, v1b); forced custom gives 0.2-0.8 ms; the generic plan is a 2-worker `Gather` of a parallel index-only scan whose startup costs ~21 ms. |
| Why do some variants get it in default mode and others not? | The statement text is shared by `count_new`, `count_failed`, `count_completed`; the generic plan is adopted when its cost is below the average custom cost, a near tie on S4 (generic 17050-17144 vs custom 17107). Whether it is adopted changes between prepares of the same variant. |
| Do generic plans explain the stable 0.2-0.5 ms gaps (E1M)? | **Largely yes, by planning time, not by a different plan:** custom plans of the `oldest_*` queries cost 0.4-0.5 ms planning for 0.05 ms execution; generic or cached plans do not. E1M itself was not re-run, so this is inferred. |
| Do generic plans explain the S4 write cells? | **No (refuted).** Identical plan mode in every variant, identical plans, yet 0.44-0.97 ms for `insert` across variants and 0.46-0.97 for the same variants in S4 vs S8-S4. Cause unresolved (run state, not plan mode). |
| Does E14 / V1-3 matter under production plan mode? | **Probably yes for `keys_*`:** `keys_*` stay on custom plans in default mode (c20/g0 in all 6 default cells of v1/v1b), where the extended statistics are used. Not measured directly for E14 under default mode (no `plancache.csv` in round 1). |

## 1. Forced modes versus default (v1, v1b)

Median ms; `T <variant>/postgres/<S>-R2`, `<S>-custom-R2`, `<S>-generic-R2` (default c/g = `PC` of the default label). Cells with a >1.5x spread and > 0.15 ms only; full rows: `python3 harness/bin/compare.py`-style diff of the three dirs.

| variant | label | query | default | custom | generic | default PC |
|---|---|---|---:|---:|---:|---|
| v1 | S2 | count_new | 0.438 | 0.396 | **21.615** | c20/g0 |
| v1 | S2 | count_failed | 0.783 | 0.789 | **21.526** | c20/g0 |
| v1 | S2 | count_statuses | 1.276 | 1.272 | **21.868** | c20/g0 |
| v1 | S4 | count_failed | 0.286 | 0.268 | **22.046** | c20/g0 |
| v1 | S4 | delete_by_status_failed (P3) | 0.132 | 0.167 | 1.894 | c20/g0 |
| v1 | S7 | count_new | 0.699 | 0.704 | **21.816** | c20/g0 |
| v1b | S2 | count_new / count_failed / count_statuses | 0.327 / 0.713 / 1.207 | 0.345 / 0.810 / 1.292 | **21.593 / 21.656 / 21.797** | c20/g0 |
| v1b | S4 | count_failed | 0.247 | 0.194 | **21.700** | c20/g0 |
| v1b | S7 | count_new | 0.692 | 0.708 | **21.737** | c20/g0 |
| v1 | S4 | count_new | 29.186 | 29.232 | 29.524 | c20/g0 (same in all modes: the plan is the same parallel scan) |
| v1 | S7 | count_failed | 21.771 | 21.689 | 21.860 | c20/g0 (genuinely 390,000 FAILED rows) |

Generic plan of `count_new` S2 (`v1/postgres/S2-generic-R2/plans/count_new.generic.txt`): `Finalize Aggregate > Gather (Workers Launched: 2) > Partial Aggregate > Parallel Index Only Scan idx_outbox_record_status`, `rows=138919` estimated for `status = $1`, execution 21.5 ms of which ~21 ms is the Gather wait.
The custom plan for `status = 'NEW'` (400 rows) is a plain index-only scan, 0.2 ms (`v1/postgres/S2-R2/plans/count_new.txt`, exec 0.156 ms). On S4 the custom plan for NEW is also parallel (`v1/postgres/S4-R2/plans/count_new.txt`, cost 17107.66, 29 ms), so custom and generic cost nearly the same there.
The generic plan for `count_failed` on S4 is that same parallel plan (`v1/postgres/S4-generic-R2/plans/count_failed.generic.txt`, cost 17144, actual 0 rows per worker, 21.4 ms) whereas the custom plan for the rare value FAILED (0 rows) is a 3-buffer index probe (cost 8.45, exec 0.035 ms).

**Hypothesis check: confirmed for these cells** (same query, same plan class, ratio 50-110x, mode is the only difference in the forced runs). The ~21 ms is parallel-worker startup in this Docker setup; the absolute value is environment-specific.

### Default mode: when does PostgreSQL adopt the generic plan?

`PC` over all 17 variants (default labels): the `count_*` statements are shared (identical SQL text, so `count_new`, `count_failed`, `count_completed` run on one server-prepared statement; their `custom_total`/`generic_total` columns include each other).
In S4, `count_new` runs first on the statement. Where its 5 custom plans cost on average more than the generic plan, the statement flips to generic and the following `count_failed` runs on the generic plan.

| label | `count_failed` default mode | variants | median |
|---|---|---|---|
| S4 | custom (c20/g0) | v1, v1b, E04, E22b, E23 | 0.198-0.286 ms |
| S4 | generic (c0/g20) | main, A1, A2, A5, A6, K12, MIN, E21, E22 | 21.57-22.39 ms |
| S8-S4 | custom | v1b, E04, E21, E22, K12 | 0.171-0.252 ms |
| S8-S4 | generic | main, v1, A1, A2, A5, A6, E22b, E23, MIN | 21.68-22.23 ms |
| S2, S8-S2 | custom (all 14 variants, `count_failed` 0.713-1.458 ms) | | |
| S5 | custom or generic, but both are 0.12-0.47 ms (the S5 generic plan is not parallel) | | |
| S7 | custom in all variants, 21.6-22.1 ms | | (FAILED has 390,000 rows: real work, not a mode effect) |

Sources: `<variant>/postgres/S4-R2|S8-S4-R2/timings.csv` and `plancache.csv`.

The mode flips between two prepares of the same snapshot: the same variant on the same S4 data, measured in the S4 label and again in the S8-S4 label (each with its own `--prepare` and order), changed mode in **6 of 14 variants** for `count_new`, `count_failed` and `count_completed` (v1, K12, E21, E22, E22b, E23), in 3 of 15 variants for `keys_nonstrict` (E21, E22b, E23), and in 0 of 14 on S2 vs S8-S2.
This is a knife-edge cost comparison (generic 17050-17144 vs custom 17107 in the v1/v1b plan files; estimated rows 414442-416832 vs 415803), so tiny differences in the freshly vacuumed statistics decide. I did not read the costs of every variant, so "knife-edge" is inferred from the v1/v1b plans.

### Cells that are bimodal because of the plan mode

| cell | modes seen | times | evidence |
|---|---|---|---|
| `count_failed` S4, S8-S4 | custom / generic | 0.17-0.29 vs 21.6-22.4 ms | table above; `v1/postgres/S4-generic-R2` 22.046, `S4-custom-R2` 0.268 |
| `count_new`, `count_completed` S4 and S5, S6 | custom / switch-to-generic | S4 29 ms in both modes (same parallel plan), S6 `count_new` 21.2 vs 7.6 ms (P3 cell in E22/E22b/E21 vs main, `T` S6) | S6 `count_new` is the same shared statement; plan not captured, so only the mode correlation is shown |
| `keys_nonstrict` S4 (main SQL) | main, E22: c4/g16; E21, E22b, E23: c20/g0 | 5860 / 5982 vs 1369 / 1366 / 1379 ms | `main|E22|E21|E22b|E23/postgres/S4-R2/plancache.csv`; the cell is 4.3x worse in generic mode, so main's S4 `keys_nonstrict` 5.9 s is a generic-plan artifact in part |
| `keys_nonstrict`, `keys_strict` S8-S4 (main SQL) | main-based variants c4/g16 | 1442-1451 ms (all same mode, so comparable among themselves) | `PC` S8-S4; the v1/E24 variants run c20/g0, so SQL-vs-main comparisons on this cell carry the mode difference |
| `find_incomplete_by_key` S2 / S8-S2 | custom 0.28-0.38 ms / generic 0.10-0.23 ms | e.g. E22 0.346 (c20/g0) vs main 0.152 (c4/g16) | `PC`; difference is planning time |
| `oldest_due_nonstrict`, `pages`, `delete_by_key_status` | custom vs generic | +0.2-0.6 ms | planning time (section 2) |
| P3 `find_completed_before`, `ids_completed_before`, `delete_by_status_failed` | custom / generic | v1 S2 254 vs 499 ms (custom is better), v1b S2 246 vs 501 ms; `delete_by_status_failed` S4 0.13 (custom) vs 1.9 ms (generic) | `v1|v1b/postgres/S2-custom-R2|S2-generic-R2`; P3 only |

Cells where the mode does **not** matter: `keys_*` (default always custom for v1/v1b), `count_partitions`, `count_statuses` on S4 (38 ms in all modes), `insert`/`upd_*`/`delete_by_id` (see section 3).

## 2. The stable 0.2-0.5 ms gap (E1M, `oldest_*`)

Planning time dominates the cheap queries. Plan files with a planning time, S2 default, v1 (`v1/postgres/S2-R2/plans/<q>.json`): `oldest_due_nonstrict` planning 0.456 ms, execution 0.048 ms; `oldest_due_strict` planning 0.476 ms, execution 0.076 ms; `keys_strict` planning 0.827 ms, execution 1.479 ms (the same for the other modes: 0.41-0.58 ms planning in custom and generic EXPLAIN).

Timings for the MIN-form `oldest_due_nonstrict` (`v1|v1b/postgres/<S>-R2|<S>-custom-R2|<S>-generic-R2`):

| variant | label | default | forced custom | forced generic | default PC |
|---|---|---:|---:|---:|---|
| v1 | S2 | 0.407 | 0.855 | 0.254 | c4/g16 |
| v1 | S4 | 0.381 | 0.762 | 0.394 | c4/g16 |
| v1 | S7 | 0.381 | 0.845 | 0.292 | c4/g16 |
| v1b | S2 | 0.380 | 0.804 | 0.376 | c4/g16 |
| v1b | S4 | 0.435 | 0.970 | 0.379 | c4/g16 |
| v1b | S7 | 0.337 | 0.809 | 0.384 | c4/g16 |

The MIN form is adopted as a generic plan after 5 executions (c4/g16) and then costs 0.25-0.44 ms. Forced custom costs +0.38-0.57 ms, which is the E1M gap (ORDER BY +0.19-0.50 ms, `E1x-v1-ablation-leave-one-out.md`, E1M table).
The ORDER BY forms in v1 behave this way: `oldest_due_strict` (ORDER BY + LIMIT) stays c20/g0 in default mode and costs 0.85-1.0 ms against 0.34-0.45 ms when forced generic (`v1|v1b/postgres/S2|S4|S7-R2` and `-generic-R2`; `oldest_created` 0.58-0.82 vs 0.34-0.48). In `v1/postgres/S2-R2/plans/oldest_due_strict.txt` the custom cost is 8.10, in the generic EXPLAIN 24091.94, which explains why PostgreSQL never adopts the generic plan for it (generic cost exceeds the custom average).

So E1M's "MIN is 0.19-0.50 ms faster" is reproduced as "a query whose generic plan PostgreSQL adopts is faster than one that stays custom". It is a statement about planning cost, which production pays too: per poll the strict/ORDER BY forms re-plan every time on every connection. E1M's ORDER BY variant was not re-run with `plancache.csv`; the evidence is the analogous v1 cells and the forced custom timing of the MIN form (0.76-0.97 ms), which equals the E1M ORDER BY timing (0.65-1.14 ms). **Partly confirmed; direct E1M re-run with `plancache.csv` outstanding.**

`keys_strict` / `keys_nonstrict` (v1, v1b) are also planning-bound: default (custom) / forced custom / forced generic: v1b S2 `keys_strict` 1.349 / 1.198 / 0.728 ms, v1 S4 1.539 / 1.197 / 0.749, v1b S4 1.251 / 1.186 / 0.790, v1b `keys_nonstrict` S4 1.222 / 1.187 / 0.633, v1 S2 `keys_strict` 2.161 / 2.194 / 1.790 (`T` of `v1|v1b` in `S2|S4-R2`, `-custom-R2`, `-generic-R2`). The difference is 0.4-0.6 ms (planning 0.5-0.8 ms of 1.2-1.5 ms). On S7 (13 ms for v1) there is no difference.

## 3. S4 write cells (`insert`, `upd_complete`, ...): hypothesis refuted

- `PC` for writes is identical in all variants: `insert`, `upd_complete`, `delete_by_id`: c4/g16; `upd_retry`, `upd_fail`: c0/g20 (the statement is shared with other queries); all 14-17 variants, all labels.
- v1 S4: default insert 0.478, custom 0.539, generic 0.559 ms; `upd_complete` 0.405 / 0.412 / 0.318 ms; v1b S4: 0.461 / 0.542 / 0.527 ms and 0.334 / 0.398 / 0.360 ms (`v1|v1b/postgres/S4-R2|S4-custom-R2|S4-generic-R2/timings.csv`). The mode changes the write cells by at most 0.2 ms.
- Across variants, S4 `insert` is 0.44-0.97 ms and `upd_complete` 0.31-1.39 ms with identical modes (`T <variant> S4-R2`). The same variants in S8-S4 show `insert` 0.36-0.55 ms and `upd_complete` 0.26-0.45 ms. In S4 the order was A6 K12 E04 MIN E21 E22 E22b E23 E24 E26 E28 main v1 v1b A1 A2 A5 (`logs/round2-pg.log`, label S4):
  `insert` 0.59 0.87 0.86 0.55 0.88 0.89 0.97 0.62 / main 0.56 v1 0.48 v1b 0.46 A1 0.53 A2 0.52 A5 0.44 ms. The values drop as the label progresses but not monotonically (A6 first = 0.59), and variants with identical DDL ranges (E04 0.86 vs v1b 0.46) differ.
- Residual explanations (not tested, no data): buffer/cache and checkpoint state after the slow multi-second queries of the same run (S4 `keys_*` take 4-6 s per rep for main-based variants, 1 ms for v1-based), dirty buffers or background writes from the preceding variant, `shared_buffers` 128 MB against a 950 MB table. A 2x positional effect of that kind is plausible but unverified.
- Single outlier: v1b S7 `insert` forced custom 2.661 ms vs 0.394 default and 0.636 generic (`v1b/postgres/S7-custom-R2/timings.csv`), with `upd_complete` 0.787 and `upd_retry` 0.670: one run, no plan difference, also unexplained.

**Conclusion: S4 (and S8-S4) write medians are not interpretable per variant.** For writes use `writecost` (WAL bytes per write, noise 0.5-1.2 %) and S8-S2.

## 4. What else is left (not plan mode)

- **Parallel plans.** The 22 ms cells are parallel-plan startup, so any parallel plan (count cells on S4/S5/S6, `keys_*` of main) carries ~20 ms latency noise that depends on worker availability. Not tested: `max_parallel_workers_per_gather = 0`.
- **Visibility map / heap fetches.** Round 1 suspected heap fetches for the `count_new` S6 bimodality. R2 `prepare` ran `VACUUM (FREEZE, ANALYZE)` before every bench; the plan files show `Heap Fetches: 243` of 1,000,000 index-only rows on S4 (`v1/postgres/S4-R2/plans/count_new.txt`), so the visibility map is not the cause here. The S6 `count_new` 21.2 vs 7.6 ms cell is not explained by this study (mode correlation only).
- **Order within the rotation.** Rotation was used for every label (`logs/round2-pg.log`). S2: main first; S4: main 12th; position matters for S4 writes (section 3), not for `count_*` (those follow mode).
- **Generic EXPLAIN capture is not reliable for every query.** `S2-generic-R2/plans/oldest_due_strict.generic.txt` shows a parallel `Gather Merge` plan with actual 21.9 ms, while the timed forced-generic reps of the same cell are 0.336 ms with `PC` c0/g20 (`v1/postgres/S2-generic-R2/timings.csv`). For `count_new` the file (21.5 ms) matches the timing (21.6 ms). So `.generic.txt` is not the plan pgjdbc ran for `oldest_due_strict`; do not use it as evidence for that cell. Cause unknown (parameter binding in the harness `PREPARE` vs pgjdbc).
- **Statement sharing.** Identical SQL text shares one server-prepared statement, so one query's mode propagates to the next (`count_new` -> `count_failed`).
- **P3 cells** (`find_completed_before`, `ids_completed_before`, `delete_by_key_status`): the same mode effect with the opposite sign (custom faster on S2: 254 vs 499 ms), only reported.

## 5. Which cells can be trusted

Trusted (mode identical between compared variants, plan change visible or effect far above noise):
- Round 2: `keys_strict`, `oldest_due_strict` etc. on S4-S7, S8-S4 where main-based and v1-based differ by 100x or more (`R2-v1b.md`, E2x), `pages` S7 E28 (plan shown), E04/A6/MIN index effects where `PC` is equal.
- Round 2 `keys_*`, `pages`, `oldest_*` cells for v1, v1b and their descendants on S2, S4, S7: default mode is identical (c20/g0 for keys and oldest_created/strict; c4/g16 for pages and `oldest_due_nonstrict`), so deltas of v1b-based variants against v1b are mode-neutral. (Check `PC` per cell; `pages` changed mode between S2 and S8-S2 in 3 of 15 variants: A5, A6, v1b.)
- Writecost (WAL B/write): not plan-dependent.

Not trusted (use only with `PC` equality):
- `count_new`, `count_failed`, `count_completed`, `count_statuses_all` in S4, S8-S4, and `keys_nonstrict`, `keys_strict` main-SQL cells on S4/S8-S4: the mode differs between variants and flips between prepares (6/14 and 3/15).
- `oldest_due_nonstrict`, `find_incomplete_by_key`, `delete_by_key_status`, `pages` differences of 0.1-0.6 ms (A2/A6/MIN stay c20/g0 on S2 `oldest_due_nonstrict`, 1.07-1.25 ms vs 0.28-0.41; those three also change the plan by dropping `idx_status_retry`, so mode and plan are confounded).
- All S4 and S8-S4 write cells per variant.
- All round 1 `count_*` cells and the E1M conclusion: no `plancache.csv` existed in round 1. The round 1 count bimodality (v1/main 21.7 ms, E11/E14/E1Q 0.2 ms, `E1x-v1-ablation-leave-one-out.md`) is consistent with this study (six of 14 variants flip between prepares) but cannot be verified for round 1. Round 1's reading "table state (heap fetches)" is not supported by R2.
- Any single-run comparison of a cell under 0.5 ms where the compared variants' `PC` differ.

## 6. Does E14 / V1-3 (extended statistics) matter under the plan mode production uses?

Production is pgjdbc defaults (`prepareThreshold = 5`) with `plan_cache_mode = auto`; the harness default mode equals it (`SET plan_cache_mode` only in forced runs, `env.txt`). One caveat: each pooled connection has its own statement and plan counters, so production pays 5 custom plans per connection and statement before the cost comparison applies.

- R2 default mode: `keys_strict` and `keys_nonstrict` are **c20/g0 in all six default cells** of v1 and v1b (S2, S4, S7). PostgreSQL stays on custom plans for the key queries because the custom plan is much cheaper than the generic estimate: v1 S2 custom cost 59 (`v1/postgres/S2-R2/plans/keys_strict.txt`: `Limit cost=1.48..59.01`) vs generic cost 1374 (`v1/postgres/S2-generic-R2/plans/keys_strict.generic.txt`: `cost=0.84..1374.22`).
- Without the statistics object (E14, round 1), the custom plan estimate is `Limit cost=453.93..453.96 rows=10` (`E14/postgres/S2-R1/plans/keys_strict.txt`, the Sort plan, actual 3.5 ms), still below the generic cost 1329.66 (`E14/postgres/S2-generic-R1/plans/keys_strict.generic.txt`). By the cost rule PostgreSQL would also stay on custom plans for E14, so the Sort plan would be what production runs.
- Round 1 measured E14 only with custom plans at 20 reps and with forced generic (no difference). Round 2 has no E14 label and no `plancache.csv` for E14, so "E14 runs custom plans in default mode" is inferred from costs, not measured.

**Assessment:** V1-3 probably matters under production plan mode for `keys_*` on S2-type data (E14: S2 `keys_strict` 2.14 -> 4.32 ms, `keys_nonstrict` 2.12 -> 4.68 ms, S10 p4 1110 -> 770 rec/s; `E1x-v1-ablation-leave-one-out.md`), and not on S4-S7, where round 1 showed no difference. Under partial ownership (S8-S2) round 1 showed no significant difference either (1.74 vs 2.20 ms). So for the default deployment the evidence for V1-3 is weak; the S2 and S10 effects are full ownership. To settle it: rerun E14 with `plancache.csv` on S8-S2 and S2, S10 with partial ownership.

Also note that generic plans would be cheaper for the key queries (0.63-0.87 ms vs 1.2-1.5 ms on v1b S2/S4), but production never gets them (cost rule), and forcing them (`force_generic_plan`) would make `count_*` 21 ms. Statistics are irrelevant if a variant were forced generic; V1-3 is a safeguard for the custom plans the planner actually keeps.

## 7. Harness changes for round 3

1. Report `PC` mode next to every default-mode median in `compare.py` and flag cells where the compared dirs differ (c20/g0 vs c4/g16 vs c0/g20). Treat differing cells as not comparable.
2. Stop statement sharing between queries: give each query a unique SQL comment (`/* id */`) so counters and modes are not shared, or `DEALLOCATE ALL` between queries. Then `count_failed` is not driven by `count_new`'s mode.
3. Run the cost-sensitive count cells (`count_new/failed/statuses`) and the cheap `oldest_*`, `pages` cells in both pinned modes in every confirm run (custom for the production-like case, generic as the worst case), not only for v1/v1b on S2/S4/S7. Round 3 screens use default mode but must carry `PC`.
4. Capture the plan that actually ran: `auto_explain` (`log_min_duration = 0`, `log_analyze`) or `EXPLAIN (GENERIC_PLAN)` (PG 16+), and make `plan()` reproduce the pgjdbc bind types, because `oldest_due_strict.generic.txt` disagrees with its timing. Report planning vs execution time separately (planning 0.4-0.8 ms is 40-90 % of the cheap cells).
5. Fix the write cells: repeat each variant at least twice at different order positions per label (or interleave A/B/A), `CHECKPOINT` and a short idle before write cells, increase reps for S4 writes; do not rank variants by S4 write medians. Keep `writecost` WAL bytes as the write metric.
6. Repeat each variant per label so the per-prepare mode flip (6 of 14 variants on S4) and the order effect are estimated; round 2 repeated only `writecost` and S10.
7. Add S8-S5, S8-S6, S8-S7 labels (partial ownership is the default; S5-S7 are full ownership only) and run the E1M and E14 variants with `plancache.csv`.
8. Optionally measure with parallel plans disabled (`max_parallel_workers_per_gather = 0`) to see whether the 21 ms cells are a Docker worker-startup artifact.

## Caveats

- One run per cell; `PC` and forced-mode runs exist only for v1 and v1b on S2, S4, S7. Other variants' modes are read from default `PC`.
- The count-statement sharing means `PC` totals include other queries; the timed deltas are exact (`harness/README.md`, "Round 2 additions").
- The cost explanation (knife-edge) rests on the v1/v1b plan files; the E14 inference rests on round 1 plan files.
- The 21 ms Gather startup is environment-specific (Docker, 6C/12T); production may see a different size.
