# Round 5, idea 3: index set with literal SQL (PostgreSQL)

Driver `harness/bin/round5-pg.sh`, 6 h 13 min, 0 failures. Two passes, reversed order, container restart. Results `results/<variant>/postgres/<label>-v<1|2>-R5/`. Cells: pass 1 / pass 2, median ms. All three use the `UABC-L` queries.

- 3a `UABC-L`: A, B, C, statistics.
- 3b `UABCD-L`: 3a + full `(status, partition_no)`.
- `D3`: 3a + `(status, partition_no) WHERE completed_at IS NULL` (added by the orchestrator from round 4).

Verdict: **`D3` is the new recommended set. 3a is second. 3b is rejected unless COMPLETED in `record-statuses` is treated as a main use.**

| Measure | 3a `UABC-L` | 3b `UABCD-L` | `D3` |
|---|---|---|---|
| S10 on S4, mean proc/s (range, 4 runs) | 1118 (1041–1260) | 1127 (1023–1235) | 1230 (1175–1297) |
| S10 on S2, mean proc/s (range, 4 runs) | 1267 (1162–1467) | 1313 (1148–1466) | 1313 (1199–1424) |
| S4 index MB | 450 | 464 | 457 |
| S4 WAL insert, 1 thread | 6833 / 6833 | 7315 / 7271 | 7201 / 7225 |
| S4 WAL `upd_complete` | 8997 / 8999 | 9435 / 9432 | 8997 / 8995 |
| S2 WAL `upd_complete` | 6295 / 6323 | 6778 / 6862 | 6302 / 6306 |
| **S8-S5 `pages`** | 62.6 / 62.4 | **132.5 / 134.3** | 60.8 / 61.1 |
| S5 `pages` | 69.6 / 68.8 | **148.6 / 148.2** | 69.0 / 67.3 |
| S8-S5 `keys_strict` | 169 / 162 | 163 / 162 | 163 / 163 |
| S8-S4 `keys_strict` | 0.89 / 0.89 | 0.89 / 0.94 | 0.99 / 0.91 |
| **S8-S4 `count_partitions`** | 48.8 / 48.5 | 49.5 / 49.3 | **10.3 / 10.1** |
| S8-S5 `count_partitions` | 15.8 / 16.0 | 16.2 / 16.2 | **1.8 / 1.8** |
| S8-S6 `count_partitions` | 22.9 / 22.7 | 22.7 / 22.8 | **2.6 / 2.6** |
| S4 `count_partitions` | 51.1 / 50.9 | 51.0 / 51.2 | 38.9 / 38.6 |
| S8-S4 `count_statuses` | 50.1 / 50.3 | 51.5 / 51.5 | 39.2 / 39.3 |
| S8-S6 `count_statuses` | 24.0 / 23.7 | 23.8 / 24.3 | 13.2 / 13.2 |
| **S4 `count_statuses_all`** | 321 / 318 | **73 / 73** | 301 / 323 |
| S2 `count_statuses_all` | 169 / 167 | **40 / 40** | 128 / 136 |

- **3b gets no count gain from literal SQL:** `count_partitions` and `count_statuses` stay at the 3a level, and `pages` still doubles (`BitmapAnd` in `UABCD-L/.../S8-S5-v*-R5/plans/pages.txt`). Its only gain is `count_statuses_all` (index-only on the new index; 3a and `D3` use a parallel seq scan, `S4-v1-R5/plans/count_statuses_all.txt`).
- **`D3` gets the count gains without the `pages` cost:** no `BitmapAnd` in any `-R5` `pages` plan. Cost: 7 MB and +6 % WAL per insert on S4; `upd_complete` WAL unchanged.
- **Why 3b `pages` doubles (`R5-pages-bitmapand.md`):** `BitmapAnd` scans every `status = 'NEW'` entry per key (199 980 TIDs, 7.8 ms x 9 keys) and removes no rows; the planner assumes `record_key` and `status` are independent. `D3` never got this plan in the tests run, which fits a planner rule about matching partial-index predicates (inferred), so the protection depends on the key index and D3 keeping the same predicate.
- **`D3` throughput:** S10 ranges overlap with 3a, so no difference is established.
- **Unexplained:** S2 `pages` for `D3` 0.90 / 0.87 vs 0.66 / 0.64, above threshold in both passes, with the same plan and about the same execution time in EXPLAIN (`S2-v*-R5/plans/pages.txt`). S8-S2 and S10 show no such gap, so an index cost looks unlikely, but the cause is open.
- **Limit:** `count_statuses_all` stays about 0.3 s on S4 with `D3`. Only someone configuring COMPLETED in `record-statuses` runs it. Round 3 also saw 1.8–2.0 s for the same plan.
