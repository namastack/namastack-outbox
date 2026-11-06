# `pages` with a top-level `ORDER BY k.k, o.created_at, o.id` (PostgreSQL)

Driver `harness/bin/pkk-pg.sh`, 1 h 23 min, 0 failures. Reference `cmp-jdbc-opt` (`01165d15`, unchanged) vs `P-KK` (same revision, only `pages` overridden, `experiments/P-KK/queries/pages.sql`). Two passes, reversed order, container restart. Results `results/{cmp-jdbc-opt,P-KK}/postgres/<label>-v<1|2>-PKK/`. Cells: median ms, pass 1 / pass 2.

Verdict: **no difference above the noise threshold, and the sort order becomes guaranteed by SQL. Adoptable.** The one consistent cost is on hot keys: about +12 ms per 18 000 rows on S8-S5, below the 30 % HOT threshold in both passes. Adoption is the user's decision; the branch is unchanged.

| Cell | Reference | `P-KK` | Result |
|---|---|---|---|
| S8-S5 `pages` (18 000 rows) | 63.6 / 61.4 | 73.6 / 75.8 | +20 %, no difference by threshold, same direction in both passes |
| S5 `pages` (20 000 rows) | 72.2 / 96.5 | 87.6 / 84.1 | no difference; reference spread is larger than the gap |
| S8-S2 `pages` | 0.72 / 0.68 | 0.77 / 0.88 | no difference |
| S8-S4 `pages` | 0.92 / 0.78 | 0.79 / 0.98 | no difference |
| S8-S6 `pages` | 0.87 / 0.67 | 0.68 / 0.77 | no difference |
| S8-S7 `pages` | 0.72 / 0.75 | 0.73 / 0.90 | no difference |
| S10 on S2, mean proc/s (range, 4 runs) | 1292 (1205–1445) | 1320 (1167–1504) | no difference, ranges overlap |
| S10 on S4, mean proc/s (range, 4 runs) | 1136 (1020–1330) | 1091 (1076–1117) | no difference, ranges overlap |
| S10 slow `pages` plans | 0 in 8 runs | 0 in 8 runs | no difference |

- **Why it is cheaper than `P-UNO`:** `ORDER BY k.k` gives an Incremental Sort with presorted key `t.k` (`P-KK/postgres/S8-S5-v1-PKK/plans/pages.txt`). `P-UNO` sorted on `o.record_key` and cost +44 % on S8-S5 in round 5 (`R5-pages-form.md`, different run, so only indicative). The presorted key as the cause of the gap is inferred.
- **Equivalence:** `results/equiv/postgres/{S2,S5,S7}/P-KK-vs-cmp-jdbc-opt.md`, 26 PASS each, no WARN or FAIL. Plan check: no queue index and no `BitmapAnd` in any S10 plan of `P-KK`.
- **Why the guarantee matters:** order inside a key otherwise relies on the inner `ORDER BY` surviving the nested loop, which SQL does not promise.
- **Not tested:** on S8-S5 the plan sorts each key's 2000 rows inside the `LATERAL` and again in the Incremental Sort. Removing the inner `ORDER BY` might remove that double sort, but it could also change the plan under load.
