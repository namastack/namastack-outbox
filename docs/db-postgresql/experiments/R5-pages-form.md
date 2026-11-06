# Round 5, idea 1: `pages` form (PostgreSQL)

Same run as `R5-d2-literal.md`. Indexes A, B, C, statistics. Cells: pass 1 / pass 2, median ms.

- 1b `UABC-L`: `unnest` + `LATERAL`, inner `ORDER BY r.created_at, r.id`.
- 1a `P-IN`: `IN`, `ORDER BY record_key, created_at, id`.
- `P-UNO`: 1b + top-level `ORDER BY o.record_key, o.created_at, o.id` (orchestrator addition).

Verdict: **keep 1b.**

| Cell | 1b | 1a `P-IN` | `P-UNO` |
|---|---|---|---|
| S8-S5 | 62.6 / 62.4 | **85.7 / 84.3** | **90.0 / 89.4** |
| S5 | 69.6 / 68.8 | **90.4 / 90.9** | **105.6 / 110.0** |
| S8-S2 | 0.68 / 0.69 | 1.11 / 0.73 | 0.97 / 0.84 |
| S8-S4 | 0.75 / 0.76 | 0.73 / 0.65 | 0.76 / 0.72 |
| S8-S6 | 0.76 / 0.75 | 0.69 / 0.68 | 0.93 / 0.69 |
| S8-S7 | 0.74 / 0.74 | 0.63 / 0.68 | 0.74 / 0.73 |
| S10 on S2, mean proc/s (4 runs) | 1267 | 1191 | 1329 |
| S10 on S4, mean proc/s (4 runs) | 1118 | 1179 | 1102 |
| S10 runs with slow `pages` plans (of 8) | 0 | **2** | 0 |

- **`P-IN` slow runs:** S10 on S2 `r2-v1` (`pages` p50 15.95 ms, 5130 plans >= 5 ms) and `r1-v2` (5.65 ms, 3954), walking `idx_outbox_record_status_retry_incomplete` with a `record_key` filter (`P-IN/.../S10-S2-r2-v1-R5/concurrency-p4-r0.plans.txt`). Not seen on S4 in 4 runs. 1b and `P-UNO` have none.
- **Hot keys:** `P-IN` sorts 18000 rows with an on-disk merge. Both 1a and `P-UNO` exceed the 30 % threshold on S5 and S8-S5.
- **Ordering risk from earlier in this round closed:** `JdbcOutboxRecordRepository.kt:198-208` (v1 worktree) groups rows by key with `groupBy`, and the contract (`OutboxRecordRepository.kt:141-157`) requires order within a page only. Order across keys does not matter, so `P-UNO` is not needed. Order inside a key still relies on the inner `ORDER BY` passing through the nested loop, which PostgreSQL does here but SQL does not promise.
- Equivalence vs v1: passed on S2, S5, S7 for `P-IN` and `P-UNO`.
