# Round 3, stage 1: screen

P1 and P2 only, default plan mode, one run per cell (write cost twice for v1b, UAB1, UABCD2). `pages` here is the `IN` form, see `R3-stage0-pages-loop.md`.
Results: `results/<variant>/postgres/<label>-R3/timings.csv`, `results/<variant>/postgres/S2-wc-R3/writecost.csv`. HOT threshold 30 %, MON 20 %, and > 0.15 ms.

## Medians, S8-S4 (1/4 ownership, 1M NEW), ms

| Variant | keys_strict | keys_nonstrict | count_statuses | count_partitions | oldest_created | oldest_due_strict | oldest_due_nonstrict |
|---|---:|---:|---:|---:|---:|---:|---:|
| v1b | 0.97 | 0.93 | 26.8 | 16.1 | 0.41 | 0.73 | 0.28 |
| E04 | 0.92 | 0.91 | 26.6 | 16.3 | 0.50 | 0.63 | 0.28 |
| UAB1 | 0.93 | 0.92 | 210 | 210 | 0.39 | 198 | 145 |
| UABC | 0.87 | 0.88 | 210 | 213 | 1.63 | 1.59 | 0.28 |
| UABD1 | 0.93 | 0.94 | 26.8 | 209 | 0.43 | 199 | 145 |
| UABD2 | 0.85 | 0.81 | 28.1 | 10.5 | 0.48 | 199 | 145 |
| UABCD1 | 0.91 | 0.93 | 27.1 | 210 | 0.44 | 0.66 | 0.33 |
| UABCD2 | 0.94 | 1.09 | 27.6 | 10.3 | 0.45 | 0.65 | 0.32 |
| UABC-L | 0.85 | 0.88 | 50.4 | 48.4 | 0.45 | 0.65 | 0.34 |
| UABCD2-NS | 0.91 | 0.92 | 27.9 | 10.2 | 0.48 | 2.77 | 0.42 |
| PA1 | 692 | 662 | 38.9 | 10.5 | 22.6 | 0.70 | 0.38 |
| PC1 | 0.85 | 0.85 | 27.5 | 10.2 | 0.38 | 198 | 16.5 |

UAB2, UAB2C, UABC2, UABC2D2, PA2 and PC2 are within noise of their counterparts in these columns. S8-S2 and S8-S7 show the same pattern at smaller size (e.g. S8-S2 `count_statuses` UAB1 99 ms vs UABCD2 1.41 ms).

## Write cost on S2

| Variant | Indexes MB | WAL/insert (1 thread) | WAL/`upd_complete` |
|---|---:|---:|---:|
| v1b | 290 | 6653, 6703 | 7455, 7554 |
| E04 | 168 | 6700 | 6992 |
| UAB1 | 80 | 6095, 6192 | 6337, 6355 |
| UABC | 81 | 6198 | 6355 |
| UABC2 | 82 | 6319 | 6405 |
| UABCD1 | 88 | 6352 | 6433 |
| UABCD2 | 88 | 6255, 6287 | 6756, 6795 |

Insert and `upd_complete` p50 latency: no difference across all variants (1.6–2.1 ms, within noise).

## Findings

- `keys_*` need only A and B: no index added to them changes P1 (except PA1).
- C is required: without it `oldest_due_strict` is 198 ms on S8-S4.
- D2 beats D1: `count_partitions` 10 vs 210 ms on S8-S4, 7 MB. It costs about 6 % WAL per `upd_complete`, since its entry changes with status.
- `UABC-L` needs no D: counts from the partial indexes, 48–50 ms at 1M NEW instead of 10–28 ms, and the WAL of a set without D. A `record-statuses` config with COMPLETED would keep the original text and a sequential scan.
- Statistics object: no P1 difference once partial indexes are used (`UABCD2-NS`). One `oldest_due_strict` cell differs (2.77 vs 0.65 ms on S8-S4).
- **User idea 4, `partition_no` in the key:** middle of the key loses (`PA1` `keys_strict` 692 ms on S8-S4, 213 ms on S16-S4; `PC1` `oldest_due_strict` 198 ms). End of the key gives no gain (`PA2`, `PC2`). Rejected.
- **1/16 ownership:** `keys_strict` stays at 0.8–0.9 ms on S16-S2 and S16-S4 for A. The cost growth with instance count that the orchestrator predicted does not appear in these scenarios (due heads are dense). S5, S6 and S7 with sparse heads at 1/16 are not measured.

## Survivors for stage 2

`v1c` (reference: v1b DDL, `unnest` pages), `UABCD2`, `UABC-L`, `UABC-L-NS`. Kept for plan changes that may help on S5/S6 only: `UAB2C-L` (B2), `UABC2-L` (C2). Dropped: all without C, D1, PA*, PC*, U0.
