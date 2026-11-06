# Round 5, idea 2: `oldest_due_nonstrict` form (PostgreSQL)

Same run as `R5-d2-literal.md`. Cells: pass 1 / pass 2, median ms.

Verdict: **keep `MIN`.**

| Cell | 2b `MIN` | 2a `ORDER BY` + `LIMIT` |
|---|---|---|
| S8-S2 | 0.19 / 0.29 | 0.66 / 0.66 |
| S8-S4 | 0.27 / 0.37 | 0.65 / 0.56 |
| S8-S5 | 0.18 / 0.16 | 0.63 / 0.61 |
| S8-S6 | 0.23 / 0.28 | 0.49 / 0.60 |
| S8-S7 | 0.21 / 0.17 | 0.62 / 0.58 |
| S4 | 0.35 / 0.43 | 0.85 / 0.86 |
| S5 | 0.37 / 0.35 | 0.93 / 0.94 |

- Same index and about the same execution (`S4-v1-R5/plans/oldest_due_nonstrict.txt`: 0.03–0.04 ms actual time).
- **Likely cause:** plan cache mode. `MIN` used generic plans in 16 of 20 timed reps; `ORDER BY` + `:limit` used custom plans in all 20, so it replans each time (`S4-v1-R5/plancache.csv`). Why the planner keeps custom plans for that form was not checked; a literal `LIMIT 1` was not tested.
- The gap is about 0.4 ms on a P2 query, but it is above the threshold in every cell, so the "same performance" condition of the `ORDER BY` preference is not met.
- Equivalence vs v1 passed on S2, S5, S7. An empty result also maps to `null` in both forms (`JdbcOutboxRecordRepository.kt:362-364`, v1 worktree).
