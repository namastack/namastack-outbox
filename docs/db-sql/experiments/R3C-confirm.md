# Round 3 stage 2: confirm — PostgreSQL

Driver `harness/bin/round3-confirm-pg.sh`, 9 h 13 min, 0 failures, 0 `PLAN CHECK BAD`, 0 slow `pages` plans in 54 S10 runs. Equivalence vs v1 on S2, S5, S7: no FAIL verdict in any of the 27 reports. Results `results/<variant>/postgres/<label>-R3C/`. Reference `v1c` = v1b DDL + v1 catalog with `unnest` `pages`. Benches, S9 and S4 write cost are single runs: only stage 3 gives a final win. Median ms unless noted.

## Partial ownership, P1 and P2

| Cell | v1c | UABCD2 | UABC-L | UA3BC-L | UA3BC3D2 |
|---|---|---|---|---|---|
| S8-S2 `keys_strict` | 1.09 | 0.94 | 0.93 | 0.83 | 0.93 |
| S8-S4 `keys_strict` | 0.97 | 0.82 | 0.86 | 0.86 | 0.99 |
| **S8-S5 `keys_strict`** | 179 | 165 | 165 | **253** | **251** |
| S8-S7 `keys_strict` | 0.98 | 0.84 | 0.89 | 0.91 | 0.85 |
| **S8-S5 `pages`** | **118** | **129** | 70 | 61 | **131** |
| S8-S4 `upd_complete` | 1.06 | 0.56 | 0.34 | 0.26 | 0.49 |
| S8-S4 `insert` | 0.89 | 0.84 | 0.54 | 0.54 | 0.70 |
| S8-S4 `upd_retry` | 0.40 | 0.68 | 0.61 | 0.97 | 1.07 |
| S8-S4 `delete_by_id` | 0.16 | 0.31 | 0.33 | 0.34 | 0.31 |
| S8-S4 `count_statuses` | 38.8 | 39.2 | 50.9 | 50.5 | 39.4 |
| S8-S4 `count_partitions` | 16.2 | 10.1 | 48.6 | 48.9 | 10.3 |
| S8-S5 `count_partitions` | 2.75 | 1.83 | 16.2 | 16.3 | 1.85 |
| S8-S4 `oldest_due_strict` | 0.70 | 0.64 | 0.89 | 0.59 | 0.80 |

Other variants: UABC-L-NS, UAB2C-L, UABC-L-UNNS0 stay close to UABC-L; exceptions below.

## Verdicts per question

- **D2 vs literal: a real trade-off.**
  - D2 costs P1: with any non-partial status index (D2, or v1c's) `pages` on S8-S5 uses `BitmapAnd` with that index: 129 vs 70 ms, 8.9 vs 1.4 ms per key (`plans/pages.txt`). Same on S5 full ownership (145 vs 77). Also WAL on S4: insert 7260 vs 6819, `upd_complete` 9420 vs 8986; +14 MB.
  - D2 gains P2: `count_partitions` S8-S4 10 vs 49, S8-S5 1.8 vs 16, `count_statuses` S8-S4 39 vs 51. Literal form under forced generic: `count_partitions` 317 ms on S8-S4 (default mode stayed custom).
  - `count_statuses_all` (COMPLETED included): 74 ms with D2, **1929 ms** without on S4, S2 40 vs 134.
- **A3: not adopted.** S8-S5 `keys_strict` 253 vs 165 ms on both A3 variants; the plan shows 23 601 heap block reads (`status_created_id` is no longer index only). Gain: 141 → 78 MB on S4, S9 after churn 343 → 284 MB. WAL no difference. Rest of the matrix: no difference, some S4/S6 `keys_strict` cells +22–28 % (below threshold).
- **A2:** already out (X1).
- **C3:** no read loss with D2 apart from a plan-mode flip in S8-S2 `oldest_due_nonstrict` (0.53 vs 0.19; pinned custom 0.54 vs 0.49, generic 0.29 vs 0.34). 41 → 31.6 MB on S4. Not isolated here (`UA3BC3D2` also has A3). Candidate for round 4, only with D2.
- **B2: no gain.** S8-S5 `pages` 60 vs 70 (below threshold), S9 equal. Out.
- **C2: worse.** S8-S4 `count_*` 124–129 vs 50, S8-S7 38 vs 30, S9 index 401 vs 343 MB. Out.
- **Statistics object (`UABC-L-NS`):** no difference in any cell above threshold, pinned plan modes included (largest: S8-S2 `keys_nonstrict` 1.38 vs 0.85, but 1.25 in UAB2C-L, which has statistics). Write cost equal. Drop candidate.
- **Client sort (`UABC-L-UNNS0`):** no gain: `pages` S8-S5 64 vs 70, S10 1251 vs 1265 proc/s, ranges overlap. Not adopted: keep the inner `ORDER BY`.
- **Correction, X1 "unexplained" `count_statuses` 28 vs 39 on S8-S4:** not caused by A. Here UABCD2 and v1c also show 39, and pinned custom shows 27.5 (UABCD2, v1c) vs 39.3 (UA3BC3D2). The cell is bimodal with the same plan; cause open. Remove the pattern claim of `R3X-screen-extension.md`.

## Write cost and throughput

| | S4 index MB | S4 WAL insert | S4 WAL `upd_complete` | S9 index MB after churn |
|---|---|---|---|---|
| v1c | 702 | 7363 | 17745 | 617 |
| UABCD2 | 465 | 7260 | 9420 | 340 |
| UABC-L | 451 | 6819 | 8986 | 343 |
| UA3BC-L | 388 | 6713 | 8990 | 284 |
| UA3BC3D2 | 393 | 7178 | 9422 | 236 |

S2 repeat: UABC-L 86 MB, `upd_complete` WAL 6331 and 6362: noise about 0.5 %. S4 was measured once, so the 47–49 % `upd_complete` gain needs repeating.

S10 (S2 data), 4 pollers, mean proc/s: v1c 1271, UABCD2 1172, UABC-L 1265, UABC-L-NS 1277, UA3BC-L 1258. Ranges overlap: **no throughput difference**. Single poller also overlaps. S10 on a large queue (S4) was not run; stage 3 adds it.

## Open

- **`upd_retry` and `delete_by_id` on S4:** v1c 0.40 and 0.14–0.16; all others 0.56–1.14 and 0.29–0.73, in both S8-S4 and S4. Above threshold, 2 labels, 8 variants. Unexplained. Offset in the same cells by `upd_complete` and `insert` gains. Stage 3 repeats it.
- **S8-S5 `keys_strict` ~165 ms is the largest P1 cell left**, while S5 at full ownership is 1.2 ms: 166k rows filtered and 115k buffers (anti-join probes on keys with many rows). Partial ownership is the default, so this is the first round 4 target.

## To stage 3

`v1c` (reference), `UABCD2`, `UABC-L`, `UABC-L-NS`.
