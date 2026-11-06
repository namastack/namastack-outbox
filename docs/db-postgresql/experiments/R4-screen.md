# Round 4 screen (PostgreSQL)

Driver `harness/bin/round4-screen-pg.sh`, 5 h 0 min (estimate 3.3 h: S9 on S4 took ~25 min per variant, not 9), 0 failures. Results `results/<variant>/postgres/<label>-R4/`. Reference `UABC-L`. Single runs: screening only. Cells are median ms. Noise thresholds from `STATE.md` (HOT/WRITE 30 %, MON 20 %, > 0.15 ms).

## Verdicts

| Variant | Verdict | Reason |
|---|---|---|
| `D3` | **To round 5, full protocol** | P2 gain at no measured P1 cost |
| `E40` (UUIDv7) | **To round 5, full protocol with reads** | Largest WAL gain so far; reads not measured yet |
| `K3` | Rejected by the user | P1 gain 33×, but the cap can return fewer keys than `:limit` (below) |
| `E08a` | Rejected | P1 worse, `oldest_created` 23 ms |
| `E08A` | Rejected | `keys_*` up to 1185 ms |
| `E07` | Rejected | Index size below threshold, no WAL or S10 gain, S9 slower |
| `E07b` | Rejected | WAL below threshold, table 14 % larger, S9 slower |

## Read cells

| Cell | UABC-L | D3 | K3 | E08a | E08A |
|---|---|---|---|---|---|
| S8-S5 `keys_strict` | 162.55 | 162.65 | **4.96** | 140.39 | 1179.21 |
| S8-S5 `keys_nonstrict` | 1.01 | 1.00 | 0.99 | 142.94 | 1185.29 |
| S8-S4 `keys_strict` | 0.91 | 0.94 | 0.94 | 3.20 | 16.24 |
| S8-S6 `keys_strict` | 0.84 | 0.89 | 0.96 | 4.79 | 512.82 |
| S8-S5 `pages` | 62.36 | 62.41 | 58.96 | 61.11 | 77.81 |
| S8-S4 `oldest_created` | 0.43 | 0.60 | 0.67 | 23.09 | 0.35 |
| S8-S4 `count_partitions` | 48.70 | **10.13** | 48.85 | 21.56 | 49.22 |
| S16-S4 `count_partitions` | 47.84 | **2.85** | 47.96 | 5.65 | 48.22 |
| S8-S5 `count_partitions` | 16.37 | **1.82** | 15.90 | 3.38 | 16.19 |
| S8-S6 `count_partitions` | 22.10 | **2.63** | 22.23 | 4.82 | 22.75 |
| S8-S4 `count_statuses` | 50.35 | 39.23 | 53.05 | 50.72 | 52.24 |
| S8-S6 `count_statuses` | 23.24 | 13.19 | 23.06 | 23.68 | 23.57 |

`E07` and `E07b` do not change reads; their cells stayed close to the reference except single-cell scatter (E07b S8-S6 `count_partitions` 26.14 vs 22.10).

## D3

`(status, partition_no) WHERE completed_at IS NULL`. `count_partitions` is an index-only scan on it (`D3/.../S8-S4-R4/plans/count_partitions.txt`). `pages` on S8-S5 stays 62 ms with a bitmap scan on the record-key index, and no `-R4` plan of D3 contains `BitmapAnd`, unlike D2.
Write cost: S4 index 458 vs 451 MB; S4 WAL insert 7318 / 7421 vs 6872 / 7015 (+6 %, one run per thread count); `upd_complete` 8991 vs 8986 / 10731; S2 no difference. S9 and S10 were not run for D3.

## E40 (UUIDv7)

| Measure | UABC-L | E40 |
|---|---|---|
| S4 WAL insert, 1 thread (2 runs) | 6872 / 6803 | **1661 / 1564** |
| S4 WAL insert, 4 threads | 7015 / 6983 | 1818 / 1818 |
| S4 WAL `upd_complete` | 10731 / 8986 | **1934 / 3719** |
| S2 WAL insert, 1 thread | 6216 / 6191 | 1564 / 1563 |
| S2 WAL `upd_complete` | 6325 / 6365 | 2861 / 1984 |
| S4 primary key MB | 153 | 209 |
| S4 all indexes MB | 451 | 507 |
| S9 on S4, indexes MB after churn | 677 | 726 |
| S10 on S4, proc/s (2 runs) | 1091 / 1151 | 1115 / 1357 |

WAL drops by about 75 % on inserts. No S10 difference is established (the 1357 run shows the unexplained fast-run jump also seen in E07b, 1403). The cause of the WAL drop and of the larger primary key was not verified. The screen did not run the read matrix. Whether update WAL reflects production depends on update order matching id order; the harness samples ordered parameters.

## K3

`keys_strict` candidates capped at the oldest 1000 before the anti-join. S8-S5: 162.55 → 4.96 ms, same row count, 9 (plan: 3402 vs 115084 buffers). Row counts match the reference in all six labels, and equivalence vs v1 passed on S2, S5, S7.
**Not measured, from reading the SQL:** in strict mode, rows behind a FAILED head stay blocked and stay oldest. If the oldest 1000 due candidates of the owned partitions are all blocked, K3 returns no keys and those partitions stop, although unblocked keys exist. S5 did not trigger this because blocked and unblocked keys are interleaved (all 9 unblocked keys were among the 1000 candidates, `K3/.../S8-S5-R4/plans/keys_strict.txt`). A fallback to the uncapped query when the capped one returns nothing would keep progress, but a poll could still return fewer keys. **User decision 2026-11-03: rejected, no fallback test.** If the 1000 candidates share one key, the query returns one key instead of `:limit`.

## E07, E07b, E08

- S9 on S4 (one run each, order UABC-L, E07, E07b): duration 1106 / 1281 / 1381 s; indexes 677 / 594 / 651 MB; table 2601 / 2416 / 2956 MB. Order may contribute to the duration gap.
- E07b S4 WAL `upd_complete` 7666 / 7664 vs 8986 / 10731: −15 % against the normal value, below the threshold. `E07` shows the same 8988 / 10730 pair as the reference.
- E07b S10 1403 / 1089, E07 1116 / 1102, reference 1091 / 1151: no established difference.
- E08a does not fix the largest P1 cell: S8-S5 `keys_strict` 140 vs 163 is below threshold, while `keys_nonstrict` there goes from 1 to 143 ms.
