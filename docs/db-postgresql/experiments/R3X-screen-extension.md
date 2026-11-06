# Round 3 screen extension (X0, X1) — PostgreSQL

Driver `harness/bin/round3-screenx-pg.sh`, 3 h 1 min, 0 failures, log `results/logs/round3-screenx-pg.log`. Results `results/<variant>/postgres/<label>-R3X/`. X1 cells are single runs: screening only. All cells are in ms, median. Partial ownership first.

## X0: `pages` forms in the S10 loop on the proposed indexes

Pollers 4, rate 0, 60 s, 4 passes, default plan mode, fresh `prepare` per run. Source: `results/PF-*/postgres/S10-def-p*-R3X/concurrency-p4-r0.txt`.

| Form | UABCD2 `pages` p50, passes 1–4 | proc/s mean | UABC `pages` p50, passes 1–4 | proc/s mean |
|---|---|---|---|---|
| PF-UN (`unnest`, inner ORDER BY) | 0.67 1.29 1.17 1.03 | 1270 | 1.00 1.33 1.31 1.32 | 1236 |
| PF-UNNS0 (`unnest`, `OFFSET 0`, client sort) | 1.32 1.31 0.81 0.57 | 1321 | 1.29 1.27 1.23 0.68 | 1228 |
| PF-IN (`IN` + ORDER BY) | **14.92** 1.10 **15.65** **9.06** | 1027 | **13.38** **9.35** 1.09 1.10 | 1110 |
| PF-NS (`IN`, client sort) | 1.10 **9.85** 1.09 0.69 | 1233 | **16.90** 1.68 **14.19** 1.11 | 989 |
| PF-ANY (`= ANY`, client sort) | **12.92** 0.99 1.06 1.05 | 1172 | **10.94** **14.56** **11.77** **16.08** | 1031 |

- Both `unnest` forms had 0 `pages` plans >= 5 ms in all 16 runs. Every other form had slow passes on both DDL sets. **`unnest` + `LATERAL` remains necessary on the proposed indexes.** Stage 0's result now holds on A, B1, C(, D2).
- Slow passes have 0 generic plans on poller 0 (only poller 0 tabulated here), consistent with stage 0: custom plans choose a queue index.
- New: in slow runs the final capture also shows C (`idx_outbox_record_status_retry_incomplete`), so the wrong index can be C as well as A. Confirm's plan check must cover both.
- PF-UN vs PF-UNNS0: no difference above S10 noise (4-pass ranges overlap, 1137–1571 vs 1180–1408).
- UABCD2 vs UABC with an `unnest` form: no throughput difference.

## X1: index variants

Source: `results/<variant>/postgres/<label>-R3X/timings.csv`, `S2-wc-R3X/writecost.csv`.

### `partition_no` leading: rejected

| Cell | UABCD2 | PL-A | PL-C | PL-AC | PAC | PD |
|---|---|---|---|---|---|---|
| S8-S4 `keys_strict` | 0.90 | **704** | 0.85 | **689** | **695** | 0.93 |
| S16-S4 `keys_strict` | 0.83 | **215** | 0.95 | **218** | – | – |
| S8-S7 `keys_strict` | 0.92 | 3.60 | 0.86 | 2.90 | 2.85 | 0.88 |
| S8-S4 `oldest_due_strict` | 0.67 | 0.69 | **198** | **658** | **657** | 0.63 |
| S16-S4 `oldest_due_strict` | 1.14 | 0.64 | **114** | **208** | – | – |
| S8-S4 `count_statuses` | 27.7 | 39.0 | 28.3 | 38.9 | 39.0 | **107** |
| S8-S4 `count_partitions` | 10.25 | 10.31 | 10.36 | 10.25 | 10.44 | 10.15 |

Cause for PL-A on S8-S4 (`plans/keys_strict.txt`): the scan loses `created_at` order across owned partitions, so the plan reads 250 815 rows, merge anti-joins, sorts top-N: 750 501 buffers. PAC (`status, partition_no, created_at, id`) has the same problem. Also S8-S2 `keys_nonstrict` 12.57 for PAC. `(status, partition_no)` stays the better D form: PD gives no `count_partitions` gain and `count_statuses` 107 vs 27.7.
The tested ownership is 1/4 and 1/16 of partitions; a single-partition owner was not tested.

### C3 `(status, next_retry_at)`

- S2: 0.5 vs 1.0 MB; WAL per write no difference. The S2 queue is small, so size at S4 is not known.
- With D2: no read difference except S8-S2 `oldest_due_nonstrict` 0.52 vs 0.33: plan-mode flip (custom vs generic) plus 9 vs 5 buffers (heap visit vs index only). S8-S4 and S8-S7 no difference.
- Without D2: **do not combine with the literal form**: S8-S4 `count_partitions` 119 (UABC3-L) vs 48 (UABC-L), because C's `INCLUDE (partition_no)` is what serves it.
- UABC3 (no D, no literal): counts 102–212 ms: no index serves them.

### A2, A3

| | A | A2 | A3 |
|---|---|---|---|
| S2 index MB | 3.8 | 2.4 | 2.2 |
| WAL insert / `upd_complete` (with D2) | 6234 / 6740 | 6191 / 6725 | 6158 / 6780 |
| `keys_strict` S8-S2, S8-S4, S8-S7 | 0.92 0.90 0.92 | 0.97 0.81 0.91 | 0.99 0.81 0.85 |
| `keys_strict` buffers S8-S2, S8-S4, S8-S7 | 40 45 39 | 57 49 66 | 58 49 67 |
| `keys_nonstrict` buffers | 87 54 46 | 65 58 73 | 66 58 74 |
| S8-S4 `count_statuses` | 27.7 | 38.9 | 39.3 |

- Time and WAL: no difference. Buffers up to +70 %: no time cost here with everything cached; a cold cache was not tested.
- A2 gives nothing over A3 here.
- `count_statuses` 39 vs 28 on S8-S4: not caused by A. The pattern seen here did not hold in confirm, where UABCD2 and v1c also show 39 (`R3C-confirm.md`). The cell is bimodal with the same plan; cause open.

### Write cost (S2)

| | index MB | WAL insert | WAL `upd_complete` | `upd_complete` p50 |
|---|---|---|---|---|
| v1b | 295.2 | 6692 | 7468 | 2.15 |
| UABCD2 | 93.4 | 6234 | 6740 | 2.08 |
| UABC-L | 86.0 | 6184 | 6362 | 2.03 |
| UA3BC-L | 84.4 | 6060 | 6359 | 2.01 |

Commit latency: no difference. D2 costs about 6 % WAL on `upd_complete` and buys S8-S4 `count_partitions` 10 vs 48, `count_statuses` 28 vs 50.

### Watch

- S16-S4 `oldest_due_strict` UABCD2 1.14 vs v1b 0.61 (single cell, S8-S4 0.67): recheck.

## Consequences for confirm

Out: PL-*, PAC, PD, `IN`/`= ANY` `pages` forms, A2, C3 without D2, UABC-L-UNNS (superseded by the `OFFSET 0` form).
Add: UA3BC-L (A3), UA3BC3D2 (smallest set that keeps D2), index sizes on S4, plan check for both queue indexes.
