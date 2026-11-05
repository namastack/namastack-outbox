# `pages` without the inner sort: `P-KK2` and `P-KK3` (PostgreSQL)

Driver `harness/bin/pkk2-pg.sh`, 2 h 51 min, 0 failures. All four variants use `cmp-jdbc-opt` (`01165d15`) with only `pages` overridden (`experiments/P-KK*/queries/pages.sql`). Two passes, reversed order, container restart. Results `results/<variant>/postgres/<label>-v<1|2>-PKK2/`. Cells: median ms, pass 1 / pass 2.

| Variant | Top-level `ORDER BY k.k, o.created_at, o.id` | Inner `ORDER BY` | `OFFSET 0` |
|---|---|---|---|
| reference (`cmp-jdbc-opt`) | no | yes | no |
| `P-KK` | yes | yes | no |
| `P-KK2` | yes | no | yes |
| `P-KK3` | yes | no | no |

Verdict: **`P-KK2` is the best form with guaranteed order: no difference from the reference by threshold, and cheaper than `P-KK` on hot keys. `P-KK3` fails: it loses about 27 % throughput on S10 S2.** Adoption is the user's decision; the branch is unchanged.

| Cell | Reference | `P-KK` | `P-KK2` | `P-KK3` |
|---|---|---|---|---|
| S8-S5 `pages` (18 000 rows) | 61.5 / 64.1 | 75.6 / 75.9 | 67.1 / 72.5 | 66.8 / 70.8 |
| S5 `pages` | 71.9 / 72.6 | 83.7 / 83.9 | 79.7 / 85.3 | 83.5 / 73.3 |
| S8-S2 `pages` | 0.71 / 0.70 | 0.71 / 0.75 | 0.71 / 0.72 | **1.27 / 1.21** |
| S8-S4 `pages` | 0.67 / 0.68 | 0.73 / 0.79 | 0.73 / 0.74 | 0.69 / 0.69 |
| S8-S6 `pages` | 0.77 / 0.68 | 0.91 / 0.73 | 0.82 / 0.72 | 0.72 / 0.69 |
| S8-S7 `pages` | 0.62 / 0.63 | 0.78 / 0.74 | 0.66 / 0.68 | 0.74 / 0.90 |
| S10 on S2, mean proc/s (range, 4 runs) | 1170 (1131–1198) | 1165 (1117–1210) | 1209 (1164–1281) | **860 (762–1046)** |
| S10 on S4, mean proc/s (range, 4 runs) | 1074 (966–1131) | 1033 (892–1125) | 1094 (1004–1168) | 1038 (945–1182) |
| S10 on S2 `pages` p50 | 1.27–1.30 | 1.28–1.38 | 1.17–1.34 | **11.6–26.2** |
| S10 on S2 `pages` p95 | 1.59–1.61 | 1.64–1.70 | 1.59–1.65 | **36–58** |
| S10 on S2 slow `pages` plans, per run | 0 | 0 | 0 | **3446–4329** |
| S10 on S4 slow `pages` plans, 4 runs in total | 1 | 1 | 0 | 0 |

`keys_strict`, also measured, shows no difference between variants.

- **Was `P-KK`'s increase real?** By the rule set beforehand, it is real but small: `P-KK` is slower than the reference on S8-S5 in both new passes (+23 %, +18 %) and was in the earlier run (`P-KK-pages-outer-order.md`). It stays below the 30 % threshold.
- **`P-KK2` vs reference on S8-S5:** +9 %, +13 %, below the threshold. Same direction in both passes, so a small cost may remain.
- **Inner sort removed:** in `P-KK2` the plan has one sort, the Incremental Sort on `t.k` (`P-KK2/postgres/S8-S5-v1-PKK2/plans/pages.txt`).
- **Why `P-KK3` fails:** without a fence the subquery is merged into a plain join, and under load the planner picks a Hash Join over `idx_outbox_record_status_partition_incomplete`, reading all `NEW` rows (`P-KK3/postgres/S10-S2-r1-v1-PKK2/concurrency-p4-r0.plans.txt`). This is the risk `OFFSET 0` was added for. On S10 S2 the `P-KK3` session stays on custom plans; the others switch to generic plans (`results/logs/pkk2-pg.log`).
- **Equivalence:** `results/equiv/postgres/{S2,S5,S7}/{P-KK2,P-KK3}-vs-cmp-jdbc-opt.md`, 26 PASS each, no WARN, FAIL or ERROR.
- **Plan check gap:** the driver's check lists only two queue indexes, not `idx_outbox_record_status_partition_incomplete`, so it reported 0 for `P-KK3`. The failure shows in the slow plan counts. Not fixed.
- **Not measured:** other dialects, because this is the PostgreSQL query only. `OFFSET 0` needs a code comment if adopted, because removing it gives the `P-KK3` plan.
