# Round 5: UUIDv7 ids, `E40` (PostgreSQL)

Same run as `R5-d2-literal.md`. Column type unchanged (`VARCHAR(255)`, DDL identical to `UABC-L`). Only id generation differs; own v7 snapshot per scenario. Cells: pass 1 / pass 2.

Verdict: **recommended on write cost and primary key size, as a code change for the user to decide.** Reads unchanged.

| Measure | v4 `UABC-L` | v7 `E40` |
|---|---|---|
| S4 WAL insert, 1 thread | 6833 / 6833 | **1564 / 1563** |
| S4 WAL insert, 4 threads | 6974 / 6966 | 2752 / 1818 |
| S4 WAL `upd_complete` | 8997 / 8999 | **1938 / 2441** |
| S2 WAL insert, 1 thread | 6198 / 6284 | 1562 / 1564 |
| S2 WAL `upd_complete` | 6295 / 6323 | 1987 / 1997 |
| S4 primary key MB | 154 | 209 |
| S4 all indexes MB | 450 | 505 |
| S8-S4 `insert` ms | 0.51 / 0.54 | 0.26 / 0.32 |
| S8-S4 `keys_strict` ms | 0.89 / 0.89 | 0.93 / 0.90 |
| S8-S5 `pages` ms | 62.6 / 62.4 | 64.0 / 61.6 |
| S10 on S4, mean proc/s (range, 4 runs) | 1118 (1041–1260) | 1239 (1079–1381) |
| S10 on S2, mean proc/s (range, 4 runs) | 1267 (1162–1467) | 1236 (1194–1318) |

- No read cell exceeded the threshold in both passes. S10 ranges overlap, so no throughput gain is established.
- **Larger primary key is a harness artefact (`R5-uuidv7-pkey.md`):** `prepare` creates the key before the load and inserts in a scan order that is not `created_at` order for 0.6 % of rows, which splits v7 pages 50/50. With ordered loads or row-by-row `uuidv7()` inserts, the v7 key on 1M rows is 59 MB vs 77 MB for v4 (24 % smaller, decimal MB like the table; `R5-uuidv7-pkey.md` quotes the same sizes as 56.3 and 74 MiB). The index sizes above and the S9 size carry this artefact and were not re-measured.
- **WAL drop stands:** with a sorted-load key the same measurement gives insert 1774 vs 1813 bytes and `upd_complete` 1357-1360 vs 1364-1383, against v4 6247 per insert and 6600-7078 per `upd_complete`. Time locality is the inferred cause; page fill is not.
- **Limits:** update WAL assumes updates follow id order roughly, as records complete oldest first; the harness samples ordered parameters. No mix of v4 and v7 rows was tested, which is what an upgraded installation would hold for a while. JDK 17 has no built-in v7 generator.
- The library change would be at `OutboxRecord.kt:241` (v1 worktree).
