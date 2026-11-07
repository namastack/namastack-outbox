# Round 1a — index sets on the problem cells (screen)

Run `r1a`, MariaDB 12.3.3, lane 1 only (lane 2 idle), one pass per cell, stage `screen`. Base `mdb-opt` `2a2663de` (worktree clean, sha verified; `mdb-main` `f4446c8c` not needed: phase 3 values are taken from `results/cmp/`). Raw data: `results/r1a/<run>/mariadb/<label>/` (timings.csv, plans/, handlers.csv, env.txt, writecost.csv, concurrency-*.txt), M8 in `results/r1a/m8/`, equivalence in `results/r1a/equiv/`, log `results/r1a/logs/r1a.log`. No failed step (`failures.txt` does not exist). Host load at every step start 0.2-3.1 (limit 18 never reached, nothing rerun).

## 0. Setup and deviations

- **Runs** (DDL `experiments/r1/<run>/ddl.sql`, all applied on 12.3.3 before timing; overlays `experiments/r1/<run>/queries/`; driver `experiments/r1/run-r1a.sh`):
  `r1-ref`/`r1-ref2` = branch DDL and SQL, first and last; `r1-m1d` i1,i2,i3; `r1-e3` i1,i2,i3 + `idx_outbox_record_status_partition (status, partition_no)`; `r1-a1` i1,i2; `r1-e1b`; `r1-e4` (+ `r1-e4q` = pack qf13 on the same schema); `r1-e5q` (pack qf14) and `r1-e5c` (pack qf15, same schema); `r1-e2q` (pack qf12). New index names: e1b `idx_outbox_record_completed_status_created_id`, `..._completed_key_created_id`, `..._completed_status_retry`, `..._status_partition`; e4 `idx_outbox_record_status_created_id_cover_completed` (the descriptive 70-character name exceeded MariaDB's 64-character limit), `..._status_retry_partition_completed`; e5 `..._status_created_id_partition_retry_key`, `..._status_retry_partition`; e2 `..._status_completed_partition_retry`, `..._status_completed_partition_created`.
- **Blocks.** Per scenario (not per label): gen once, then for each run one `prepare` and all labels of that scenario on that schema (bench rolls back its writes), ref first and `r1-ref2` last. `r1-e4q` and `r1-e5c` run on the schema just prepared for `r1-e4` / `r1-e5q` with `--only` (e4q: `oldest_created`, `oldest_due_nonstrict`; e5c: those two plus `keys_nonstrict`); the other queries of e4q are the branch SQL of `r1-e4`. Writecost and S10 are solo cells with their own `prepare`; e1b/e4/e5q/e3/m1d/a1/e2q each once, ref twice.
- **Limits.** 10 s on S4, S5, S6, S7, S8-S4, S16-S4; S8-S5 and S8-S7: max(2 x ref median, 60 s) (`--limit-from` the ref's timings). ">lim" below = stopped at the limit.
- **Harness change (documented in README "MariaDB port").** `bench --pages-keys-file F`: `pages` uses the keys in `F` instead of the `keys_strict` result. Used on label `S4` for all runs with `experiments/r1/s4-pages-keys.txt` (first 10 distinct keys of due NEW rows in `created_at, id` order; this is the `created_at` fallback of the spec, `mdb-main` was not run). On S4 `pages` is therefore measured for every run (the ref's `keys_strict` is stopped). On S5/S6 `pages` still takes its keys from `keys_strict`, so it shows ">lim" for ref and `r1-e2q`.
- **Equivalence** (mdb-opt vs run, S1 and S5, `HARNESS_SAMPLE_ORDERED=1`) for `r1-e4q`, `r1-e5q`, `r1-e5c`, `r1-e2q`: **all queries PASS (0 FAIL, 0 WARN)** (S1 28 verdicts, S5 26 each). S1/S5 do not exercise rows violating NEW <=> `completed_at IS NULL`, so the E5 invariant is assumed, not tested.
- **Noise/drift.** Thresholds from STATE.md (HOT 11 %, MON 10 % / 0.04 ms). Ref vs `r1-ref2` agree within threshold on every label and query except `count_partitions` on the S4 block (S8-S4 98 -> 116 ms, S16-S4 94 -> 106, S4 109 -> 128; +13..18 %): that query is flagged in that block. Only one WRITE group cell exceeds the WRITE threshold (S7 `r1-e5q` `upd_complete` 0.32 -> 0.53 ms; deferred). S10 and writecost are single runs.
- Cells vs phase 3: ref values on partial ownership are lower than the `cmp` branch values (quieter host: load 1-3 instead of 11-15, and a re-prepared schema); compare runs with the ref of this run, and with `mdb-main` only for order of magnitude. `count_statuses` of `mdb-main` is `count_new`+`count_failed` (not in the tables).

## 1. Results per run

Tables (median ms, all runs side by side, partial ownership first). "main p1 / p2" are the phase 3 `mdb-main` medians (rerun values where a rerun exists). WRITE rows (insert/upd_*/delete_by_id) are omitted: no difference above the WRITE threshold except the single deferred cell above.


#### S8-S4

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.10 / 1.19 | 0.49 | 0.50 | 0.38 | 0.50 | 0.38 | 0.51 | 0.49 | - | 0.51 | - | 0.63 |
| keys_nonstrict | 27557 / 24818 | 0.55 | 0.54 | 0.41 | 0.53 | 0.40 | 0.56 | 0.54 | - | 0.55 | 0.55 | 0.67 |
| pages | 0.54 / 0.53 | 0.34 | 0.32 | 0.31 | 0.33 | 0.33 | 0.31 | 0.32 | - | 0.32 | - | 0.32 |
| count_statuses | - / - | 111.3 | 117.1 | 121.6 | 103.3 | 139.2 | 101.5 | 101.0 | - | 99.4 | - | 117.1 |
| count_partitions | 61.3 / 62.1 | 98.1 | 115.7 | 114.3 | 27.3 | 150.2 | 28.2 | 28.0 | - | 27.7 | - | 33.2 |
| oldest_created | 15214 / 14052 | 161.6 | 162.3 | 162.4 | 162.3 | 163.9 | 162.3 | 160.9 | 0.34 | 0.26 | 148.3 | 42.1 |
| oldest_due_strict | 21952 / 22394 | 0.36 | 0.37 | 0.28 | 0.38 | 1138 | 0.37 | 0.38 | - | 0.37 | - | 1243 |
| oldest_due_nonstrict | 90.9 / 90.6 | 142.4 | 142.0 | 142.4 | 142.0 | 171.6 | 141.1 | 142.9 | 0.53 | 0.41 | 131.3 | 49.6 |

#### S16-S4

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.54 / 0.54 | 0.42 | 0.43 | 0.37 | 0.43 | 0.36 | 0.45 | 0.43 | - | 0.42 | - | 0.47 |
| keys_nonstrict | 6604 / 5112 | 0.46 | 0.46 | 0.40 | 0.46 | 0.38 | 0.47 | 0.46 | - | 0.45 | 0.48 | 0.51 |
| pages | 0.33 / 0.32 | 0.32 | 0.32 | 0.32 | 0.34 | 0.32 | 0.32 | 0.32 | - | 0.32 | - | 0.31 |
| count_statuses | - / - | 111.1 | 117.0 | 121.2 | 103.3 | 141.9 | 101.4 | 101.1 | - | 99.5 | - | 117.1 |
| count_partitions | 7.57 / 7.46 | 93.7 | 106.0 | 106.1 | 6.84 | 139.6 | 6.95 | 6.95 | - | 6.93 | - | 8.25 |
| oldest_created | 5640 / 4827 | 5076 | 5502 | 145.5 | 5077 | 145.6 | 5556 | 5694 | 0.25 | 0.17 | 5390 | 10.5 |
| oldest_due_strict | 8075 / 7376 | 0.30 | 0.31 | 0.23 | 0.30 | 848.5 | 0.29 | 0.29 | - | 0.27 | - | 7557 |
| oldest_due_nonstrict | 11.6 / 11.3 | 5048 | 5464 | 120.6 | 121.6 | 147.9 | 5550 | 5651 | 0.29 | 0.20 | 5283 | 12.3 |

#### S8-S5

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 14919 / 15350 | 107.4 | 107.2 | 107.4 | 107.9 | 107.4 | 105.0 | 105.6 | - | 104.3 | - | 109.0 |
| keys_nonstrict | 2441 / 2431 | 4.79 | 5.00 | 4.79 | 4.84 | 4.73 | 4.87 | 4.87 | - | 4.85 | 2162 | 5.00 |
| pages | 5080 / 4810 | 1279 | 1288 | 1279 | 1313 | 1297 | 1282 | 1268 | - | 1274 | - | 1264 |
| count_statuses | - / - | 21.3 | 21.4 | 23.8 | 19.9 | 28.5 | 20.2 | 20.2 | - | 21.4 | - | 23.4 |
| count_partitions | 8.68 / 8.80 | 19.5 | 19.5 | 24.7 | 4.00 | 29.6 | 4.04 | 3.99 | - | 3.99 | - | 4.83 |
| oldest_created | 2440 / 2361 | 31.4 | 31.3 | 31.6 | 31.4 | 31.4 | 31.5 | 30.7 | 0.31 | 0.22 | 28.7 | 6.08 |
| oldest_due_strict | 2606 / 2586 | 0.58 | 0.57 | 0.50 | 0.59 | 122.6 | 0.59 | 0.56 | - | 0.57 | - | 123.5 |
| oldest_due_nonstrict | 12.8 / 12.8 | 29.4 | 29.2 | 29.2 | 29.2 | 32.7 | 29.6 | 29.5 | 0.46 | 0.33 | 27.8 | 7.17 |

#### S8-S7

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.56 / 0.55 | 0.45 | 0.45 | 0.38 | 0.44 | 0.36 | 0.45 | 0.46 | - | 0.45 | - | 0.51 |
| keys_nonstrict | 5.97 / 5.85 | 0.48 | 0.50 | 0.42 | 0.48 | 0.40 | 0.49 | 0.49 | - | 0.48 | 0.51 | 0.58 |
| pages | 0.35 / 0.35 | 0.31 | 0.32 | 0.32 | 0.32 | 0.30 | 0.32 | 0.33 | - | 0.33 | - | 0.31 |
| count_statuses | - / - | 68.9 | 68.7 | 69.7 | 68.7 | 76.7 | 62.0 | 69.0 | - | 65.2 | - | 68.7 |
| count_partitions | 0.59 / 0.57 | 1.14 | 1.12 | 1.41 | 0.54 | 1.66 | 0.50 | 0.56 | - | 0.56 | - | 0.66 |
| oldest_created | 3.05 / 3.11 | 1.84 | 1.88 | 1.75 | 1.84 | 1.76 | 1.82 | 1.81 | 0.30 | 0.21 | 1.77 | 0.78 |
| oldest_due_strict | 7.98 / 7.79 | 0.32 | 0.32 | 0.22 | 0.31 | 7.58 | 7.53 | 0.31 | - | 0.31 | - | 7.91 |
| oldest_due_nonstrict | 0.83 / 0.77 | 1.95 | 2.11 | 1.74 | 1.93 | 1.91 | 1.90 | 1.92 | 0.44 | 0.32 | 1.88 | 0.92 |

#### S4

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 2.52 / 1.58 | >lim | >lim | 0.57 | 0.67 | 0.54 | 0.69 | 0.68 | - | 0.67 | - | >lim |
| keys_nonstrict | 115029 / 85037 | >lim | >lim | 0.58 | 0.69 | 0.55 | 0.69 | 0.70 | - | 0.73 | 0.70 | >lim |
| pages | 0.57 / 0.33 | 0.33 | 0.33 | 0.32 | 0.35 | 0.31 | 0.32 | 0.34 | - | 0.32 | - | 0.34 |
| count_statuses | - / - | 111.5 | 117.1 | 121.1 | 103.4 | 139.2 | 101.4 | 101.1 | - | 99.5 | - | 117.0 |
| count_partitions | 482.0 / 358.5 | 109.3 | 127.9 | 128.1 | 111.0 | 163.3 | 113.2 | 115.9 | - | 110.6 | - | 132.9 |
| oldest_created | 97141 / 79279 | >lim | >lim | 201.6 | >lim | 201.9 | >lim | >lim | 0.51 | 0.34 | >lim | 168.9 |
| oldest_due_strict | 30212 / 19974 | >lim | >lim | 0.38 | 0.43 | 2771 | 0.43 | 0.42 | - | 0.41 | - | >lim |
| oldest_due_nonstrict | 97042 / 75281 | >lim | >lim | 204.5 | >lim | 228.5 | >lim | >lim | 0.54 | 0.36 | >lim | >lim |

#### S5

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 4.69 / 4.67 | >lim | >lim | 3.34 | 3.37 | 3.27 | 3.37 | 3.36 | - | 3.37 | - | >lim |
| keys_nonstrict | 14408 / 13314 | >lim | >lim | 2.70 | 2.71 | 2.63 | 2.75 | 2.75 | - | 2.76 | 2198 | >lim |
| pages | 4648 / 4265 | >lim | >lim | 1567 | 1595 | 1555 | 1552 | 1573 | - | 1519 | - | >lim |
| count_statuses | - / - | 21.4 | 21.6 | 24.0 | 19.9 | 28.7 | 20.3 | 19.9 | - | 21.5 | - | 23.3 |
| count_partitions | 13281 / 13122 | 22.1 | 22.3 | 27.5 | 27.4 | 32.7 | 20.9 | 22.5 | - | 25.1 | - | 27.0 |
| oldest_created | 3229 / 2825 | >lim | >lim | 39.8 | 39.8 | 40.1 | 39.9 | 39.0 | 0.47 | 0.26 | 36.4 | 34.3 |
| oldest_due_strict | 3724 / 3428 | >lim | >lim | 0.62 | 0.66 | 491.6 | 0.67 | 0.66 | - | 0.65 | - | >lim |
| oldest_due_nonstrict | 38.0 / 37.3 | >lim | >lim | 42.0 | 42.3 | 46.3 | 42.6 | 42.1 | 0.61 | 0.39 | 39.7 | >lim |

#### S6

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 417.5 / 385.0 | >lim | >lim | 0.75 | 0.85 | 0.62 | 0.85 | 0.85 | - | 0.82 | - | >lim |
| keys_nonstrict | 405.1 / 372.5 | >lim | >lim | 0.77 | 0.86 | 0.63 | 0.84 | 0.88 | - | 0.85 | 0.88 | >lim |
| pages | 0.36 / 0.35 | >lim | >lim | 0.31 | 0.33 | 0.31 | 0.32 | 0.33 | - | 0.31 | - | >lim |
| count_statuses | - / - | 21.5 | 22.0 | 23.4 | 19.8 | 28.0 | 21.0 | 19.9 | - | 20.2 | - | 23.5 |
| count_partitions | 24.7 / 24.8 | 22.3 | 22.9 | 27.5 | 27.5 | 32.5 | 23.4 | 27.6 | - | 30.9 | - | 27.2 |
| oldest_created | 7516 / 7072 | >lim | >lim | 41.1 | 40.4 | 40.3 | 40.2 | 39.6 | 0.47 | 0.30 | 37.5 | 34.9 |
| oldest_due_strict | 442.6 / 383.3 | >lim | >lim | 0.39 | 0.49 | 66.2 | 0.52 | 0.50 | - | 0.49 | - | >lim |
| oldest_due_nonstrict | 1.77 / 1.76 | >lim | >lim | 1.42 | 1.56 | 38.4 | 1.54 | 1.53 | 0.62 | 0.42 | 1.59 | >lim |

#### S7

| query | main p1/p2 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e4q | r1-e5q | r1-e5c | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.23 / 1.21 | 839.5 | 826.5 | 0.58 | 0.61 | 0.54 | 0.64 | 0.62 | - | 0.60 | - | 838.6 |
| keys_nonstrict | 885.0 / 758.0 | 833.5 | 822.8 | 0.59 | 0.63 | 0.54 | 0.65 | 0.63 | - | 0.62 | 0.67 | 831.7 |
| pages | 0.35 / 0.33 | 0.32 | 0.30 | 0.32 | 0.31 | 0.30 | 0.32 | 0.31 | - | 0.32 | - | 0.32 |
| count_statuses | - / - | 68.4 | 69.1 | 69.2 | 68.6 | 76.6 | 61.9 | 68.8 | - | 65.0 | - | 68.6 |
| count_partitions | 818.8 / 890.4 | 1.43 | 1.39 | 1.65 | 1.72 | 1.89 | 1.53 | 1.81 | - | 1.62 | - | 1.87 |
| oldest_created | 818.4 / 883.6 | 817.5 | 800.4 | 2.28 | 2.35 | 2.24 | 2.32 | 2.32 | 0.46 | 0.30 | 2.27 | 2.28 |
| oldest_due_strict | 852.7 / 914.7 | 848.1 | 826.0 | 0.37 | 0.43 | 20.9 | 0.40 | 0.42 | - | 0.41 | - | 852.1 |
| oldest_due_nonstrict | 2.76 / 2.72 | 813.0 | 791.4 | 2.53 | 2.62 | 2.62 | 2.62 | 2.65 | 0.61 | 0.41 | 2.58 | 813.0 |

(`pages` in the S5/S6 tables comes from each run's own `keys_strict`; ">lim" there is the stopped `keys_strict`, not a `pages` problem. Phase 3 note: `mdb-main` S4 `pages` 0.33-0.57 ms with its own keys.)

### 1.1 Per-run findings (screen)

**r1-ref (branch).** The phase 3 collapse is reproduced. Full ownership: `keys_strict` and `keys_nonstrict` stopped at 10 s on S4, S5, S6 and 840 ms on S7 (`mdb-main` 2.0, 4.7, 400, 1.2 ms); all `oldest_*` except `oldest_due_strict` on S4/S5/S6 stopped (`oldest_created` S7 818 ms). S10-S2-p1-unthr 2.0 / 2.2 rec/s, keys p95 44-48 s. S4-wc insert redo 5.7 KB/write (`mdb-main` 2.6 KB). Plans: `idx_outbox_record_status_completed_partition` with `est=256`, filesort (see M8).

**r1-m1d (i1, i2, i3).** *Collapse fixed.* `keys_strict` S4/S5/S6/S7 0.57 / 3.3 / 0.75 / 0.58 ms (`mdb-main` 2.0 / 4.7 / 400 / 1.2), `keys_nonstrict` 0.58 / 2.7 / 0.77 / 0.59 (`mdb-main` 85-115 s / 14 s / 390 / 820 ms), `pages` on S4 0.32. S10-S2-p1-unthr 450 rec/s, keys p95 2.4 ms (`mdb-main` 343-394). Write cost: S4-wc insert redo 2.3 KB (main level, ref -59 %), secondary index total -19 % (520 MB vs 640 MB on S4). P2 with the branch SQL: `oldest_created` / `oldest_due_nonstrict` are covering-index scans of the whole NEW range: S4 202 / 205 ms (`mdb-main` 79-97 s each), S5 40 / 42 ms (main 3.0 s / 38 ms), S6 41 / 1.4 ms, S7 2.3 / 2.5 ms; S16-S4 146 / 121 ms (main 5.6 s / 11.6 ms: `oldest_due_nonstrict` stays 10x slower than main). `count_partitions` is not improved: S16-S4 106 ms (main 7.5), S8-S5 24.7 (main 8.7). `oldest_due_strict` 0.2-0.6 ms everywhere. Remaining cost: P2 scans of the NEW backlog (MON group, accepted by ADR-0005 unless a form fixes them: r1-e4q/e5q).

**r1-e3 (i1, i2, i3, `(status, partition_no)`).** Collapse fixed in the same way as m1d (keys S4/S5/S6/S7 0.67 / 3.4 / 0.85 / 0.61; S10 395 rec/s, keys p95 2.8 ms). `count_partitions` fixed as expected (exact multi-range seek): S16-S4 6.8 ms (main 7.6, ref 94), S8-S4 27 (main 61), S8-S5 4.0 (main 8.7), S5 27 ms (main 13.3 s). But with the branch SQL the optimizer now sends `oldest_created` / `oldest_due_nonstrict` through `(status, partition_no)` plus a primary-key lookup per row: S4 stopped at 10 s (m1d 202 / 205 ms), S16-S4 5.1 s (m1d 146 ms); S8-S4/S5/S6/S7 as m1d. Write cost: S4-wc insert redo 5.7 KB (unchanged vs ref, +117 % vs main; the new index grows 113 -> 163 MB during the 5 000 + 5 000 inserts like B-1d), S2-wc `upd_complete` redo 10.3 KB (ref 4.5-4.7 KB, +120 %) and `delete_by_id` redo 7.6 KB (ref 1.8 KB, +310 %). Net: replacing i4 by `(status, partition_no)` fixes R1/R3-count but keeps (and for S2 worsens) the write cost and breaks the MIN forms at S4.

**r1-a1 (i1, i2).** Collapse fixed for the key queries and the lowest write cost of all (3 indexes, 393 MB; S4-wc insert redo 2.25 KB; S10 469 rec/s, keys p95 1.7 ms; `keys_strict` 0.54 ms on S4). But `oldest_due_strict` loses its ordered index: S4 2 771 ms, S5 492, S6 66, S7 21, S16-S4 849, S8-S4 1 138 (every other run 0.2-0.7 ms, main 25-30 s on S4, 3.6 s on S5). `count_partitions` and `count_statuses` are the slowest of all runs (S16-S4 140 ms, S4 `count_statuses` 139 vs 111 ms). Not viable: M7-minimal is a loss on `oldest_due_strict`.

**r1-e1b (completed_at-leading, `(status, partition_no)`).** Collapse fixed (keys S4 0.69, S10 414 rec/s, keys p95 5.4 ms: the highest p95 of the fixed runs, single run). `count_partitions` fixed (S16-S4 7.0 ms, S8-S5 4.0, S8-S7 0.50). With the branch SQL `oldest_created` / `oldest_due_nonstrict` behave like e3 (S4 stopped, S16-S4 5.6 s / 5.6 s; S5 40 / 43 ms, S7 2.3 / 2.6 ms). New loss: S8-S7 `oldest_due_strict` 7.5 ms vs 0.3 ms (outer scan on `(completed_at, status, ...)` + filesort of 10 000 rows; S4/S5/S6/S7 0.4-0.7 ms). Write cost: S4-wc insert redo 5.8 KB (+120 % vs main, like e3), `upd_complete` redo 4.8 KB in S4-wc (ref 14.6 KB, -67 %: completions land in a time-ordered `completed_at` region), but S2-wc `upd_complete` 9.1 KB (+95 %) and `delete_by_id` 7.0 KB (+280 %). The `count_statuses` of this DDL is not worse (`(status, partition_no)` serves it, S4 101 vs 111 ms).

**r1-e4 (branch SQL) and r1-e4q (pack qf13).** Index effect alone (branch SQL): like e3 (keys 0.68 / 3.4 / 0.85 / 0.62; `count_partitions` fixed; `oldest_created` / `oldest_due_nonstrict` stopped on S4 and 5.7 s on S16-S4; S10 422 rec/s). Query effect (`oc-03-order-id`, `odn-01-order`, ORDER BY ... LIMIT 1): `oldest_created` 0.34 / 0.25 / 0.31 / 0.47 / 0.47 / 0.30 / 0.46 ms and `oldest_due_nonstrict` 0.53 / 0.29 / 0.46 / 0.61 / 0.62 / 0.44 / 0.61 ms on S8-S4 / S16-S4 / S8-S5 / S5 (and S6, S8-S7, S7 as listed in the tables): the R4 cells are solved (`mdb-main`: 14-97 s on S4, 11.6 ms on S16-S4 `oldest_due_nonstrict`). Plan: ordered range on `(status, next_retry_at, partition_no, completed_at)`, 15 rows examined at S16-S4, `r_total_time_ms` 0.008. Write cost as e3 (index `(status, partition_no)`): S4-wc insert redo 5.7 KB, S2-wc `upd_complete` 10.6 KB, `delete_by_id` 7.8 KB; index total 633 MB (S4).

**r1-e5q (pack qf14) and r1-e5c (pack qf15).** `r1-e5q`: all five P1/P2 queries are as fast as e4q (keys S4/S5/S6/S7 0.67 / 3.4 / 0.82 / 0.60; `oldest_created` 0.34 (S4), 0.17 (S16-S4), 0.22 (S8-S5), 0.26 (S5), 0.30 (S6), 0.30 (S7); `oldest_due_nonstrict` 0.36 / 0.20 / 0.33 / 0.39 / 0.42 / 0.41), i.e. 100-100 000 times faster than `mdb-main` on the R4 cells and faster than main on every P2 cell. S10 411 rec/s, keys p95 3.3 ms. Write cost as e3/e4 (S4-wc insert redo 5.7 KB, S2-wc completion 10.6 KB and delete 7.7 KB; index total 622 MB, -3 % vs ref, 5 indexes). Equivalence PASS (S1, S5). `r1-e5c` (controls): `kn-13-no-completed` (inner `completed_at` dropped too) is as bad as `mdb-main` where the NEW rows of a key are many: S5 2.2 s, S8-S5 2.2 s (main 14 s / 2.4 s; fine on S4/S6/S7 at 0.5-0.9 ms); the MIN forms `oc-07` / `odn-05` are as slow as the branch MIN (S4 stopped, S16-S4 5.3-5.4 s, S5 36 / 40 ms): the improvement of e5q comes from ORDER BY ... LIMIT 1, not from dropping the outer `completed_at`.

**r1-e2q (pack qf12).** *Does not fix the collapse*: `keys_strict` / `keys_nonstrict` stopped on S4, S5, S6 and 839 / 832 ms on S7, S10 2.0 rec/s (keys p95 47 s): the new indexes `(status, completed_at, partition_no, ...)` get the same `est=256`. The per-partition loose-index-scan forms work only when the owned partition count is small: P2 at partial ownership S16-S4 `oldest_created` 10.5 ms / `oldest_due_nonstrict` 12.3 ms (ref 5.1 s, main 5.6 s / 11.6 ms; m1d 146 / 121 ms), S8-S5 6.1 / 7.2 ms (main 2.4 s / 12.8 ms), S8-S7 0.78 / 0.92 ms; at full ownership S4 `oldest_created` 169 ms, `oldest_due_nonstrict` stopped, S5 34 ms / stopped. `count_partitions` (cp-04, status-conditional) S16-S4 8.3 ms (main 7.6), S8-S5 4.8. `oldest_due_strict` stopped at full ownership (S4/S5/S6), 7.6 s on S16-S4, 1.2 s on S8-S4 (main 8 s / 22 s). Write cost is the best of the "partition" variants: S4-wc insert redo 2.7 KB (+5 % vs main), `upd_complete` S2-wc 5.7 KB (main 5.6), index total 647 MB.

## 2. Full ownership and S10-S2-p1-unthr (summary)

Full-ownership collapse (S4/S5/S6/S7 `keys_strict`, S10): **fixed by every run that has no index `(status, completed_at, partition_no[, x])`**: `r1-m1d`, `r1-e3`, `r1-a1`, `r1-e1b`, `r1-e4`(q), `r1-e5q/c`. Not fixed by `r1-e2q` (its third/fourth index keep `partition_no` right after `completed_at`; same false `est=256`). S10-S2-p1-unthr (60 s, rate 0, 1 poller):

| run | processed rec/s | keys p50 ms | keys p95 ms | insert rec/s | history list end |
|---|---|---|---|---|---|
| mdb-main (phase 3 p1) | 342.6 | 7.58 | 28.49 | 2251.6 | 2235 |
| mdb-main (phase 3 p2) | 394.0 | 7.42 | 20.11 | 2578.5 | 1431 |
| mdb-opt (phase 3 p1) | 1.9 | 1.42 | 40151.72 | 2132.3 | 158779 |
| r1-ref | 2.0 | 0.86 | 47946.04 | 2338.2 | 150153 |
| r1-ref2 | 2.2 | 0.87 | 44236.23 | 2576.1 | 162438 |
| r1-m1d | 449.7 | 0.57 | 2.36 | 2507.8 | 118 |
| r1-e3 | 395.2 | 0.63 | 2.76 | 2421.1 | 186 |
| r1-a1 | 468.8 | 0.53 | 1.72 | 2598.0 | 1170 |
| r1-e1b | 414.1 | 0.63 | 5.43 | 2561.2 | 1286 |
| r1-e4 | 422.2 | 0.63 | 2.77 | 2589.1 | 1495 |
| r1-e5q | 411.3 | 0.61 | 3.29 | 2553.2 | 961 |
| r1-e2q | 2.0 | 1.46 | 46680.48 | 2501.9 | 158715 |

(Throughput differences among the fixed runs, 395-469 rec/s, are one run each and below the resolution of this harness on this host: deferred. `mdb-main` 343-394 in phase 3; all fixed runs are at or above that level and reach a drained history list.)

## 3. Write cost and index sizes (writecost `S2-wc` small backlog, `S4-wc` 1 M NEW backlog; per write; redo noise 12 %, p50 8 %, p95 25 %)


#### S2-wc

| metric | main p1 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e5q | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|
| ins1 redo B | 2396 | 2366 | 2400 | 2241 | 2260 | 2112 | 2261 | 2353 | 2323 | 2377 |
| ins1 p50 | 0.72 | 0.65 | 0.65 | 0.65 | 0.65 | 0.64 | 0.65 | 0.65 | 0.65 | 0.65 |
| ins1 p95 | 1.05 | 0.80 | 0.80 | 0.81 | 0.80 | 0.79 | 0.80 | 0.81 | 0.80 | 0.80 |
| ins1 pages | 1.35 | 1.25 | 1.26 | 1.25 | 1.25 | 1.24 | 1.23 | 1.25 | 1.31 | 1.26 |
| ins4 redo B | 2242 | 2298 | 2311 | 2250 | 2370 | 2117 | 2276 | 2289 | 2314 | 2345 |
| ins4 p50 | 1.16 | 1.15 | 1.16 | 1.16 | 1.16 | 1.16 | 1.16 | 1.16 | 1.17 | 1.17 |
| compl redo B | 5644 | 4504 | 4738 | 4467 | 10339 | 4280 | 9109 | 10644 | 10565 | 5671 |
| compl p50 | 0.72 | 0.62 | 0.67 | 0.68 | 0.76 | 0.64 | 0.74 | 0.70 | 0.78 | 0.65 |
| compl p95 | 1.04 | 0.86 | 0.87 | 0.90 | 1.03 | 0.83 | 0.96 | 0.98 | 1.02 | 0.87 |
| retry redo B | 2726 | 3166 | 3212 | 3099 | 3014 | 2933 | 3073 | 3057 | 3053 | 3173 |
| retry p50 | 0.69 | 0.63 | 0.63 | 0.64 | 0.63 | 0.64 | 0.62 | 0.62 | 0.62 | 0.61 |
| fail redo B | 4831 | 3835 | 4023 | 3099 | 3943 | 2616 | 3997 | 3916 | 3934 | 4810 |
| fail p50 | 0.74 | 0.64 | 0.69 | 0.64 | 0.65 | 0.66 | 0.68 | 0.64 | 0.67 | 0.64 |
| del redo B | 3012 | 1832 | 1856 | 1979 | 7559 | 1889 | 6996 | 7764 | 7719 | 2048 |
| del p50 | 0.65 | 0.58 | 0.56 | 0.56 | 0.55 | 0.57 | 0.56 | 0.54 | 0.57 | 0.54 |
| index count | 6.00 | 5.00 | 5.00 | 4.00 | 5.00 | 3.00 | 5.00 | 5.00 | 5.00 | 5.00 |
| idx total MB before | 374 | 348 | 348 | 281 | 342 | 210 | 342 | 342 | 332 | 352 |

#### S4-wc

| metric | main p1 | r1-ref | r1-ref2 | r1-m1d | r1-e3 | r1-a1 | r1-e1b | r1-e4 | r1-e5q | r1-e2q |
|---|---|---|---|---|---|---|---|---|---|---|
| ins1 redo B | 2613 | 5680 | 5689 | 2338 | 5679 | 2254 | 5762 | 5664 | 5742 | 2732 |
| ins1 p50 | 0.74 | 0.70 | 0.71 | 0.70 | 0.72 | 0.66 | 0.76 | 0.72 | 0.72 | 0.66 |
| ins1 p95 | 1.24 | 0.94 | 0.95 | 0.93 | 0.98 | 0.85 | 1.06 | 0.98 | 0.96 | 0.89 |
| ins1 pages | 1.47 | 2.52 | 2.51 | 1.26 | 2.63 | 1.25 | 2.76 | 2.55 | 2.55 | 1.48 |
| ins4 redo B | 2397 | 3178 | 3141 | 2276 | 3036 | 2244 | 3170 | 3139 | 3182 | 2420 |
| ins4 p50 | 1.25 | 1.30 | 1.31 | 1.22 | 1.32 | 1.18 | 1.32 | 1.35 | 1.32 | 1.17 |
| compl redo B | 15436 | 14620 | 14528 | 13815 | 14759 | 13603 | 4822 | 15609 | 15012 | 14221 |
| compl p50 | 1.07 | 0.91 | 0.91 | 0.88 | 0.92 | 0.79 | 0.82 | 0.97 | 0.93 | 0.96 |
| compl p95 | 2.85 | 1.33 | 1.39 | 1.35 | 1.53 | 1.07 | 1.32 | 1.64 | 1.90 | 1.96 |
| retry redo B | 3710 | 14215 | 14282 | 14305 | 14465 | 13974 | 14379 | 14086 | 14980 | 14307 |
| retry p50 | 0.69 | 0.78 | 0.78 | 0.78 | 0.80 | 0.70 | 0.77 | 0.79 | 0.84 | 0.79 |
| fail redo B | 3912 | 4159 | 4164 | 3969 | 4150 | 3766 | 4156 | 4204 | 4116 | 4052 |
| fail p50 | 0.74 | 0.64 | 0.64 | 0.63 | 0.69 | 0.57 | 0.63 | 0.65 | 0.64 | 0.75 |
| del redo B | 1656 | 1816 | 1817 | 1719 | 2113 | 1613 | 2191 | 2111 | 2114 | 1844 |
| del p50 | 0.73 | 0.61 | 0.61 | 0.61 | 0.61 | 0.61 | 0.54 | 0.61 | 0.61 | 0.66 |
| index count | 6.00 | 5.00 | 5.00 | 4.00 | 5.00 | 3.00 | 5.00 | 5.00 | 5.00 | 5.00 |
| idx total MB before | 704 | 640 | 640 | 520 | 633 | 393 | 633 | 633 | 622 | 647 |

Index sizes before writes (S4 table, MB, primary key 2 656 MB): ref 181 / 212 / 119 / 127 (i2/i1/i4/i3, total 640); m1d 181 / 212 / 127 (520); a1 181 / 212 (393); e3 181 / 212 / 127 / 113 `(status, partition_no)` (633); e1b 181 / 212 / 127 / 113 (633); e4 181 / 212 (cover) / 127 / 113 (633); e5q 181 / 205 / 121 / 113 (622); e2q 181 / 212 / 126 / 126 (647). During the S4 writes `(status, partition_no)` grows 113 -> 163 MB (+44 %), B-1d 119 -> 170 MB (+42 %); no other index grows more than 10 %. Observation: the S4-wc insert redo of 5.7 KB (vs 2.3-2.7 KB) appears with every DDL that has a status-leading narrow index on `partition_no` (`B-1d`, `(status, partition_no)`) and not with `r1-e2q` (partition_no third, then more columns) or without such an index; the same index makes `upd_complete` and `delete_by_id` redo on the small S2 backlog 2.2-4x higher (completed rows land in the middle of a large, partition-ordered status region).

## 4. M8 — `eq_range_index_dive_limit` (plans only, no timings; `ANALYZE FORMAT=JSON`, session limit 200 unless stated)

Data: `results/r1a/m8/<S5|S7>/<r1-ref|r1-m1d>/<query>-p<n>-dive<k>.json` and `summary.csv` (one row per ANALYZE: chosen index, `rows` estimate vs `r_rows`, `r_total_time_ms`). Owned partitions are evenly spread (`i*256/n`); `SET STATEMENT SQL_SELECT_LIMIT=10` for keys, `1` for `oldest_due_*`, none for `oldest_created`/`count_partitions`. All 192 plans were executed with `ANALYZE` (longest 16 s), none replaced by `EXPLAIN`.

- **Branch DDL, S5 (200 k NEW), `keys_strict`:** 16 / 64 / 199 owned partitions choose `idx_outbox_record_status_completed_created_id` (est 408 356 from index dives/statistics, `r_rows` 24 at 199; 65 / 110 / 11 ms), **from 200 owned partitions on it chooses `idx_outbox_record_status_completed_partition` with `rows` = number of owned partitions (200, 201, 256: one row per value, false) and `r_rows` 150 000-200 000, 11-15 s** (the flip is at 200, i.e. `n >= eq_range_index_dive_limit`, not 201). `keys_nonstrict`, `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` flip at the same point (`oldest_created` 10-14 s); `count_partitions` uses `status_completed_retry` at all n. With the session limit 0 (always dive) or 300 at 256 partitions the plan is the good one: `keys_strict` 4.3 / 3.0 ms, `keys_nonstrict` 3.1 / 2.2 ms, `oldest_due_strict` 0.4 ms (an observation, not an adoptable setting). On S7 (10 000 NEW) the same flip costs 0.6-0.8 s instead of 11-15 s. At 16 partitions the branch DDL already picks B-1d on S7 (est 604 = `r_rows` 604, 96 ms vs 0.1 ms with i1: a correct estimate but a non-covering choice; S5 `oldest_created` / `oldest_due_nonstrict` at 16 partitions: 426 / 422 ms vs 31-55 ms with i1/i3).
- **`r1-m1d`, S5 and S7:** no plan change over 16 ... 256 partitions or the session limit: `keys_*` use i1 (`status_completed_created_id`) with the key-index probe, `oldest_due_*` i3 (`status_completed_retry`), `oldest_created` i1, `count_partitions` i3; all `keys_*` 2-3 ms on S5 (up to 110 ms at 16-64 partitions, where the first owned NEW key is further away: `r_rows` 126-199 980), `oldest_*` 30-43 ms (S5) / 2 ms (S7) at all n. The estimates (408 356, 410 718) do not depend on n (statistics-based), so there is no cliff.
- Expected "estimate drops to about 1 row per partition from 201 partitions" was close but off by one: the drop starts at 200 owned partitions (n >= limit).

## 5. Ranking

Full-ownership targets are `mdb-main`'s values; "vs main" uses the phase 3 numbers in section 1 (same label). Verdict per ADR-0005 (P1/P2 first, read benefit must outweigh write cost; P3 not judged).

| rank | run | collapse fixed (S4-S7 keys, S10) | P2 (oldest_*, count_partitions) | write cost | verdict vs ref | vs `mdb-main` |
|---|---|---|---|---|---|---|
| 1 | `r1-m1d` (i1, i2, i3) | yes (S10 450 rec/s) | oldest_* scan the NEW range: 2-205 ms (S4 202 / 205 ms); `count_partitions` not better | best-but-one: S4 insert redo = main, -19 % index bytes | **win** | P1 at or better than main; P2 better on S4/S16-S4 `oldest_created`, 10x worse on S16-S4 `oldest_due_nonstrict` and `count_partitions` |
| 2 | `r1-e5q` (+ qf14) | yes (S10 411) | all P2 < 1 ms except `count_partitions` 4-27 ms (fixed at partial ownership); needs the NEW <=> `completed_at IS NULL` invariant | **loss**: S4 insert redo +120 % vs main, S2 completion/delete redo x2-4 | **mixed** (read win, write loss) | at or better than main on all measured read cells except `count_partitions` S6 (31 vs 25 ms); write cost worse than main on S4-wc inserts |
| 3 | `r1-e4q` (+ qf13) | yes (S10 422) | as e5q (ORDER BY ... LIMIT 1; `completed_at` kept in index so forms stay valid without the invariant) | as e5q | **mixed** | as e5q |
| 4 | `r1-e3` | yes (S10 395) | `count_partitions` fixed; `oldest_*` with MIN stopped on S4, 5 s on S16-S4 (worse than m1d) | loss as e5q | mixed | `count_partitions` at main level; S4 `oldest_*` >10 s (main 80-97 s) |
| 5 | `r1-e1b` | yes (S10 414) | as e3 + S8-S7 `oldest_due_strict` 7.5 ms | loss as e5q (S4 completion redo -67 %) | mixed / loss | as e3 |
| 6 | `r1-e4` (branch SQL) | yes (S10 422) | as e3 | loss as e5q | no gain over e3 | as e3 |
| 7 | `r1-a1` (i1, i2) | yes (S10 469) | `oldest_due_strict` S4 2.8 s, S5 0.5 s, S6 66 ms, S7 21 ms; `count_*` slowest | lowest (3 indexes) | loss (P2 `oldest_due_strict`) | still better than main on `oldest_due_strict` (S4 2.8 s vs 20-30 s, S7 21 vs 850 ms) but 2-4 orders worse than every other fixed run; not viable |
| 8 | `r1-e2q` | **no** (S10 2.0 rec/s, S4/S5/S6 keys stopped) | loose scan good at S8/S16 (S16-S4 10-12 ms) | insert redo main level | no difference to ref on P1 | worse than main at full ownership |
| - | `r1-e5c` (controls) | n/a | `kn-13` S5 2.2 s, MIN forms like branch | as e5q | control: confirms ORDER BY LIMIT 1 is the lever | - |

Reading: the collapse is a pure `(status, completed_at, partition_no)` problem (R1/R2): dropping it (`m1d`) is sufficient for P1 and restores the write cost. P2 (R3/R4) needs a different lever: ORDER BY ... LIMIT 1 forms (e4q/e5q, 0.2-0.6 ms) remove the whole-backlog scans, the `oldest_*` plans of e4q/e5q use the status-leading ordered indexes (`status, created_at, ...` and `status, next_retry_at, partition_no`), not `(status, partition_no)`, which only serves `count_partitions`. The write loss of e3/e4/e5q/e1b comes from `(status, partition_no)` alone.

Suggested round 1b base DDL: `r1-m1d` (i1, i2, i3), plus a variant `m1d + E4/E5 forms` (indexes e4/e5 without the fourth index `(status, partition_no)`) to check whether the ORDER BY forms keep their 0.2-0.6 ms without it, and `count_partitions` with the M2-like form (not measured here).

## 6. Deferred-test candidates

- S10 throughput/p95 differences among the fixed runs (395-469 rec/s, p95 1.7-5.4 ms): one run each, single pollers; only the fixed-vs-collapsed difference is reliable.
- Small P1 differences between `m1d`/`a1` (keys_strict 0.54-0.58 ms) and e3/e1b/e4/e5q (0.67-0.69 ms) on S4 and the same pattern on S5/S6/S7 (0.1 ms absolute, 15-20 %): above the HOT threshold but sub-millisecond; one pass.
- `count_statuses` differences (-10 % e3/e1b/e4/e5q, +25 % a1 vs ref): borderline for MON.
- S4-block `count_partitions` ref vs ref2 drift (+13..18 %): every `count_partitions` comparison on S4/S16-S4/S8-S4 of ~15 % is unreliable.
- S7 `r1-e5q` `upd_complete` 0.32 -> 0.53 ms (WRITE rolled-back single statement, single cell): noise-class, ignore unless repeated.
- Writecost latency p50/p95 and `pages_written`: all within or near noise; only redo bytes differences (>12 %) are reported as results.
- `pages` on S4 uses fallback keys (not main's `keys_strict` keys): identical across runs, but not the scheduler's keys.
- Not measured: S10 and S4-wc for `r1-e4q`/`r1-e5c` (same DDL as `r1-e4`/`r1-e5q`, by spec); Part A/C of the spec (round 1b).
