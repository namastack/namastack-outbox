# SUMMARY: run `cmp`

Final comparison, `mdb-main` vs `mdb-opt`, MariaDB 12.3.3 (`mariadb:12`, id 2215e9af9663), lane 1 only (lane 2 idle for the whole run), harness `docs/db-mariadb/harness/`. Trees: `pass1/`, `pass2/`, `rerun1/`, `pass*/equiv/`. Generated tables below come from per-query `timings.csv` medians (script kept outside the repo); raw files are in the trees.

## 0. Run record

- Variants (worktrees clean, `git status --short -- . ':!docs'` empty at start): `mdb-main` `f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f`, `mdb-opt` `2a2663de2f2028790180399293981f7eca587ad1` (both as expected; DDL sha256 main 55debcd6..., opt a5c0dad0..., from `env.txt`).
- Pass 1 order main, opt; container stopped/started; pass 2 order opt, main, scenarios in reverse order. Base and candidate of a cell back to back on the same snapshot. Per-query limit: main 900 s; opt `--limit-from` pass-1 main timings of the same label (2x, min 60 s; 900 s when main has no median), in both passes.
- **Reused from calibration** (shas, DDL sha256, catalog, image all match; harness files unchanged since the committed catalog): `S8-S2`, `S8-S4`, `S8-S5` (bench) and `S2-wc` (writecost), both variants, pass 1 from `calibration/pass1`, pass 2 from `calibration/pass2`. Note the calibration limits were 900 s for opt in pass 2 and main-derived in pass 1.
- Completed cells: bench S1, S2, S4, S5, S6, S7, S8-S2, S8-S4, S8-S5, S8-S6, S8-S7, S16-S2, S16-S4, S4-b100; writecost S2-wc, S4-wc; S10-S2-p1, -p1-unthr, -p4, -p4-unthr, S10-S4-p4, -p4-unthr (60 s, rate 500 / 0). All in both passes, except S9.
- **S9: pass 1 only (pass 2 skipped by user decision; repeated in phase 6).** Churn on mdb-main hit the 2700 s retention cap (removed 28,000 vs 500,000 on the branch), so main's post-churn table holds about 1.3M COMPLETED rows against about 0.83M for opt. Its post-churn P1/P2 timings are measured against a larger table and are not like for like (details in section 4).
- Equivalence (`HARNESS_SAMPLE_ORDERED=1`): S1, S2, S5, S7, run once in pass 1 (section 6). No per-query limit option exists for `equiv`; none was needed (all finished).
- Host load: outside load (another tenant). Pass 1 steps started at loadavg 2-15.5 (max 15.46, below the 18 rule; S1, S2, S16-S2, S4 early cells at 11-15, later cells at 2-4); pass 2 and the rerun at 1-4. No step started above 18.

## 0.1 Reruns, harness changes, anomalies

- **Reruns for the pass-to-pass spread rule** (`rerun1/`, bench only, main then opt, same snapshot and limits, load 1-2): S8-S7, S7, S1, S2, S16-S2, S4, S16-S4, S4-b100 (pass-1 spreads 25-100 % under outside load 11-15 vs pass 2 at load 1-2). Rerun results are section 1b. After the rerun, one query still exceeds 2x threshold: S4-b100 `oldest_due_nonstrict` mdb-main (pass 2: 412.6 ms with 20 reps, i.e. a different plan; pass 1 92.9 s and rerun 75.5 s, 1 execution each).
- Other reruns: S9 churn restarted once (below); S10-S2-p1 and -p1-unthr cells restarted (below). No rerun for load > 18.
- **Harness changes** (documented in README "MariaDB port"): (1) `bin/run-cells.sh` label mapping for `S16-*` and `S10-<S>-p<n>[-unthr]`; (2) `bench churn --retention-max-s N` (mdb-main's retention select takes about 2 min per 1,000-row batch on the S9 table, 500 batches ~16 h; first attempt was killed after 55 min with ~27 batches done, its result tree deleted and rerun with `--retention-max-s 2700` for both variants; churn took 2950 s for main). Harness rebuilt once (`:sql:installDist`) before the affected cells.
- Driver bug (mine): the first launch of the S10 cells passed pollers/rate in the wrong order (labels p500-r500 etc.); those four cells (both variants) were stopped, their trees deleted and rerun correctly. A mid-run edit of the driver script made the first pass-1 driver exit at its end with a shell syntax error after all cells had finished; no cell was affected.
- Observations: mdb-opt S4 / S4-b100 `keys_strict` and `pages` (and `oldest_due_strict`) were stopped at the 60 s limit in both passes and the rerun ("over 60 s"), while mdb-main answers them in about 2 ms; mdb-main `keys_nonstrict` and the `oldest_*` queries on S4 take 15-115 s. mdb-opt S10-S2-p1-unthr processed only ~2 rec/s (keys query p95 ~40 s) vs mdb-main ~340-390 rec/s (section 5).
- No failures (no `FAILED`/`ABORT` entries in `logs/*failures.txt`); free disk stayed above 44 GB.

Numbers are measured medians (ms) unless stated. `*` = very slow query (1 warm-up/1 execution or 1+5 reps per harness rule). `over N s` = stopped by the per-query limit. Change = (opt - main) / main on the mean of both passes' medians; `(no difference)` = below the group noise threshold (HOT 11 %, MON 10 % or 0.04 ms absolute for < 0.3 ms, WRITE 60 % and >= 0.2 ms). `SPREAD>2x` marks a variant/query whose pass-1 vs pass-2 spread exceeds 2x the threshold (HOT 22 %, MON 20 % or 0.08 ms for < 0.3 ms, WRITE 120 % and >= 0.2 ms).

`count_statuses` of mdb-main = `count_new` + `count_failed` (main has no grouped count).

## 1. Bench timings (HOT, WRITE, MON), partial-ownership labels first

### S8-S2

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 0.929 | 0.995 | 0.780 | 0.774 | -19% |  |
| keys_nonstrict | 1.634 | 1.662 | 0.791 | 0.785 | -52% |  |
| pages | 0.655 | 0.624 | 0.513 | 0.490 | -22% |  |
| insert | 0.655 | 0.716 | 0.554 | 0.515 | -22% (no difference) |  |
| upd_complete | 0.491 | 0.575 | 0.466 | 0.379 | -21% (no difference) |  |
| upd_retry | 0.259 | 0.358 | 0.339 | 0.250 | -5% (no difference) |  |
| upd_fail | 0.284 | 0.299 | 0.380 | 0.282 | +14% (no difference) |  |
| delete_by_id | 0.469 | 0.430 | 0.402 | 0.405 | -10% (no difference) |  |
| count_statuses | 3.622 | 3.608 | 4.702 | 4.778 | +31% |  |
| count_partitions | 0.499 | 0.496 | 0.474 | 0.521 | +0% (no difference) |  |
| oldest_created | 0.952 | 0.972 | 0.716 | 0.674 | -28% |  |
| oldest_due_strict | 2.163 | 2.216 | 0.513 | 0.499 | -77% |  |
| oldest_due_nonstrict | 0.586 | 0.597 | 0.871 | 0.862 | +46% |  |
| count_new | 0.348 | 0.345 | 0.368 | 0.384 | +9% (no difference) |  |
| count_failed | 3.274 | 3.263 | 3.486 | 3.472 | +6% (no difference) |  |
| count_completed | 188.7 | 186.3 | 204.5 | 203.2 | +9% (no difference) |  |
| count_partition_single | 0.133 | 0.138 | 0.353 | 0.370 | +167% |  |

### S8-S4

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 1.105 | 1.187 | 0.848 | 0.907 | -23% |  |
| keys_nonstrict | 27557.2 | 24817.9 | 0.922 | 1.006 | -100% |  |
| pages | 0.543 | 0.534 | 0.534 | 0.536 | -1% (no difference) |  |
| insert | 0.692 | 0.920 | 0.595 | 0.612 | -25% (no difference) |  |
| upd_complete | 0.873 | 0.957 | 0.949 | 0.880 | -0% (no difference) |  |
| upd_retry | 0.342 | 0.487 | 0.588 | 0.367 | +15% (no difference) |  |
| upd_fail | 0.237 | 0.301 | 0.417 | 0.249 | +24% (no difference) |  |
| delete_by_id | 0.543 | 0.554 | 0.612 | 0.524 | +4% (no difference) |  |
| count_statuses | 175.8 | 176.8 | 218.1 | 221.6 | +25% |  |
| count_partitions | 61.279 | 62.060 | 198.0 | 196.6 | +220% |  |
| oldest_created | 15214.2 | 14051.8 | 296.6 | 297.7 | -98% |  |
| oldest_due_strict | 21951.6 | 22394.5 | 0.604 | 0.588 | -100% |  |
| oldest_due_nonstrict | 90.860 | 90.553 | 278.1 | 281.5 | +208% |  |
| count_new | 175.7 | 176.6 | 189.9 | 190.8 | +8% (no difference) |  |
| count_failed | 0.117 | 0.156 | 0.123 | 0.129 | -8% (no difference) |  |
| count_completed | 189.1 | 189.1 | 206.1 | 205.3 | +9% (no difference) |  |
| count_partition_single | 0.930 | 0.927 | 172.3 | 175.5 | +18633% |  |

### S8-S5

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 14919.0 | 15349.7 | 197.1 | 196.0 | -99% |  |
| keys_nonstrict | 2440.6 | 2431.3 | 8.829 | 9.041 | -100% |  |
| pages | 5080.1 | 4809.8 | 1491.0 | 1459.5 | -70% |  |
| insert | 0.716 | 0.630 | 0.602 | 0.540 | -15% (no difference) |  |
| upd_complete | 0.668 | 0.734 | 0.498 | 0.502 | -29% (no difference) |  |
| upd_retry | 0.351 | 0.346 | 0.324 | 0.324 | -7% (no difference) |  |
| upd_fail | 0.325 | 0.322 | 0.325 | 0.330 | +1% (no difference) |  |
| delete_by_id | 0.449 | 0.503 | 0.354 | 0.395 | -21% (no difference) |  |
| count_statuses | 35.729 | 35.769 | 44.291 | 44.867 | +25% |  |
| count_partitions | 8.683 | 8.796 | 39.551 | 39.176 | +350% |  |
| oldest_created | 2440.4 | 2361.3 | 58.160 | 58.093 | -98% |  |
| oldest_due_strict | 2605.6 | 2585.9 | 1.064 | 1.089 | -100% |  |
| oldest_due_nonstrict | 12.825 | 12.832 | 56.601 | 57.125 | +343% |  |
| count_new | 35.612 | 35.650 | 38.505 | 38.390 | +8% (no difference) |  |
| count_failed | 0.117 | 0.119 | 0.126 | 0.127 | +7% (no difference) |  |
| count_completed | 57.495 | 57.325 | 62.052 | 62.120 | +8% (no difference) |  |
| count_partition_single | 0.534 | 0.540 | 34.943 | 35.569 | +6465% |  |

### S8-S6

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 4.782 | 4.832 | 0.719 | 0.715 | -85% |  |
| keys_nonstrict | 3.109 | 3.209 | 0.771 | 0.767 | -76% |  |
| pages | 0.376 | 0.365 | 0.312 | 0.318 | -15% |  |
| insert | 0.606 | 0.547 | 0.406 | 0.322 | -37% (no difference) |  |
| upd_complete | 0.438 | 0.500 | 0.598 | 0.547 | +22% (no difference) |  |
| upd_retry | 0.238 | 0.256 | 0.395 | 0.295 | +40% (no difference) |  |
| upd_fail | 0.210 | 0.221 | 0.277 | 0.269 | +27% (no difference) |  |
| delete_by_id | 0.416 | 0.342 | 0.421 | 0.411 | +10% (no difference) |  |
| count_statuses | 17.653 | 17.826 | 23.161 | 21.841 | +27% |  |
| count_partitions | 6.849 | 6.438 | 22.797 | 19.837 | +221% |  |
| oldest_created | 4301.7 | 3795.6 | 34.353 | 33.173 | -99% |  |
| oldest_due_strict | 4.384 | 4.221 | 0.419 | 0.411 | -90% |  |
| oldest_due_nonstrict | 0.546 | 0.543 | 1.196 | 1.174 | +118% |  |
| count_new | 17.580 | 17.752 | 20.466 | 18.980 | +12% |  |
| count_failed | 0.073 | 0.074 | 0.075 | 0.085 | +9% (no difference) |  |
| count_completed | 74.943 | 76.404 | 86.321 | 82.290 | +11% |  |
| count_partition_single | 0.184 | 0.164 | 18.090 | 17.175 | +10034% |  |

### S8-S7

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 0.557 | 0.555 | 0.463 | 0.449 | -18% |  |
| keys_nonstrict | 5.973 | 5.884 | 0.616 | 0.488 | -91% | SPREAD>2x mdb-opt 26% |
| pages | 0.354 | 0.356 | 0.337 | 0.312 | -9% (no difference) |  |
| insert | 0.502 | 0.537 | 0.454 | 0.434 | -15% (no difference) |  |
| upd_complete | 0.784 | 0.807 | 0.501 | 0.369 | -45% (no difference) |  |
| upd_retry | 0.333 | 0.342 | 0.287 | 0.145 | -36% (no difference) |  |
| upd_fail | 0.305 | 0.292 | 0.362 | 0.214 | -4% (no difference) |  |
| delete_by_id | 0.361 | 0.367 | 0.402 | 0.336 | +1% (no difference) |  |
| count_statuses | 48.598 | 49.269 | 69.117 | 69.421 | +42% |  |
| count_partitions | 0.587 | 0.560 | 1.121 | 1.171 | +100% |  |
| oldest_created | 3.053 | 3.061 | 1.848 | 1.869 | -39% |  |
| oldest_due_strict | 7.984 | 7.875 | 0.306 | 0.318 | -96% |  |
| oldest_due_nonstrict | 0.826 | 0.750 | 1.911 | 1.929 | +144% |  |
| count_new | 0.965 | 0.957 | 1.020 | 1.036 | +7% (no difference) |  |
| count_failed | 47.633 | 48.312 | 53.533 | 53.957 | +12% |  |
| count_completed | 56.024 | 57.199 | 60.713 | 61.229 | +8% (no difference) |  |
| count_partition_single | 0.099 | 0.085 | 0.951 | 0.964 | +941% |  |

### S16-S2

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 1.045 | 0.617 | 1.009 | 0.616 | -2% (no difference) | SPREAD>2x mdb-main 69%; SPREAD>2x mdb-opt 64% |
| keys_nonstrict | 0.650 | 0.392 | 1.040 | 0.626 | +60% | SPREAD>2x mdb-main 66%; SPREAD>2x mdb-opt 66% |
| pages | 0.629 | 0.377 | 0.545 | 0.316 | -14% | SPREAD>2x mdb-main 67%; SPREAD>2x mdb-opt 72% |
| insert | 0.942 | 0.631 | 0.605 | 0.356 | -39% (no difference) |  |
| upd_complete | 0.614 | 0.420 | 0.468 | 0.228 | -33% (no difference) |  |
| upd_retry | 0.350 | 0.256 | 0.349 | 0.155 | -17% (no difference) |  |
| upd_fail | 0.392 | 0.202 | 0.313 | 0.183 | -16% (no difference) |  |
| delete_by_id | 0.462 | 0.332 | 0.392 | 0.316 | -11% (no difference) |  |
| count_statuses | 3.736 | 1.845 | 4.746 | 2.468 | +29% | SPREAD>2x mdb-main 102%; SPREAD>2x mdb-opt 92% |
| count_partitions | 0.231 | 0.149 | 0.448 | 0.227 | +78% | SPREAD>2x mdb-main 0.082 ms; SPREAD>2x mdb-opt 0.221 ms |
| oldest_created | 0.343 | 0.216 | 0.346 | 0.220 | +1% (no difference) | SPREAD>2x mdb-main 0.127 ms; SPREAD>2x mdb-opt 0.126 ms |
| oldest_due_strict | 0.726 | 0.438 | 0.786 | 0.477 | +9% (no difference) | SPREAD>2x mdb-main 66%; SPREAD>2x mdb-opt 65% |
| oldest_due_nonstrict | 0.286 | 0.182 | 0.417 | 0.257 | +44% | SPREAD>2x mdb-main 0.104 ms; SPREAD>2x mdb-opt 0.160 ms |
| count_new | 0.337 | 0.199 | 0.395 | 0.192 | +10% (no difference) | SPREAD>2x mdb-main 0.138 ms; SPREAD>2x mdb-opt 0.203 ms |
| count_failed | 3.399 | 1.646 | 3.559 | 1.762 | +5% (no difference) | SPREAD>2x mdb-main 107%; SPREAD>2x mdb-opt 102% |
| count_completed | 187.0 | 91.583 | 199.6 | 99.229 | +7% (no difference) | SPREAD>2x mdb-main 104%; SPREAD>2x mdb-opt 101% |
| count_partition_single | 0.133 | 0.096 | 0.383 | 0.204 | +156% | SPREAD>2x mdb-opt 0.179 ms |

### S16-S4

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 0.542 | 0.525 | 0.426 | 0.441 | -19% |  |
| keys_nonstrict | 6604.5 | 5135.6 | 0.466 | 0.468 | -100% | SPREAD>2x mdb-main 29% |
| pages | 0.328 | 0.311 | 0.307 | 0.330 | -0% (no difference) |  |
| insert | 0.676 | 0.647 | 0.479 | 0.478 | -28% (no difference) |  |
| upd_complete | 0.783 | 0.740 | 0.745 | 0.746 | -2% (no difference) |  |
| upd_retry | 0.328 | 0.324 | 0.399 | 0.404 | +23% (no difference) |  |
| upd_fail | 0.287 | 0.280 | 0.174 | 0.178 | -38% (no difference) |  |
| delete_by_id | 0.457 | 0.481 | 0.564 | 0.488 | +12% (no difference) |  |
| count_statuses | 86.734 | 85.528 | 108.2 | 107.1 | +25% |  |
| count_partitions | 7.569 | 7.476 | 94.115 | 94.244 | +1152% |  |
| oldest_created | 5640.4 | 5430.2 | 5298.0 | 5130.0 | -6% (no difference) |  |
| oldest_due_strict | 8074.8 | 7839.0 | 0.298 | 0.299 | -100% |  |
| oldest_due_nonstrict | 11.552 | 11.477 | 5293.3 | 4810.9 | +43776% |  |
| count_new | 86.643 | 85.445 | 93.663 | 93.393 | +9% (no difference) |  |
| count_failed | 0.091 | 0.083 | 0.102 | 0.089 | +10% (no difference) |  |
| count_completed | 96.704 | 93.808 | 102.0 | 102.5 | +7% (no difference) |  |
| count_partition_single | 0.504 | 0.489 | 86.746 | 85.830 | +17279% |  |

### S1

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 1.621 | 0.952 | 1.044 | 0.602 | -36% | SPREAD>2x mdb-main 70%; SPREAD>2x mdb-opt 73% |
| keys_nonstrict | 1.918 | 1.110 | 1.030 | 0.619 | -46% | SPREAD>2x mdb-main 73%; SPREAD>2x mdb-opt 66% |
| pages | 0.677 | 0.382 | 0.564 | 0.302 | -18% | SPREAD>2x mdb-main 77%; SPREAD>2x mdb-opt 87% |
| insert | 0.325 | 0.214 | 0.295 | 0.272 | +5% (no difference) |  |
| upd_complete | 0.294 | 0.176 | 0.286 | 0.249 | +14% (no difference) |  |
| upd_retry | 0.223 | 0.140 | 0.212 | 0.130 | -6% (no difference) |  |
| upd_fail | 0.221 | 0.148 | 0.219 | 0.171 | +6% (no difference) |  |
| delete_by_id | 0.118 | 0.079 | 0.123 | 0.074 | +0% (no difference) |  |
| count_statuses | 0.288 | 0.168 | 0.199 | 0.129 | -28% | SPREAD>2x mdb-main 0.120 ms |
| count_partitions | 0.625 | 0.358 | 0.511 | 0.299 | -18% | SPREAD>2x mdb-main 75%; SPREAD>2x mdb-opt 0.212 ms |
| oldest_created | 0.631 | 0.366 | 0.532 | 0.311 | -15% | SPREAD>2x mdb-main 72%; SPREAD>2x mdb-opt 71% |
| oldest_due_strict | 1.786 | 1.039 | 0.679 | 0.412 | -61% | SPREAD>2x mdb-main 72%; SPREAD>2x mdb-opt 65% |
| oldest_due_nonstrict | 0.952 | 0.546 | 0.776 | 0.466 | -17% | SPREAD>2x mdb-main 74%; SPREAD>2x mdb-opt 67% |
| count_new | 0.156 | 0.092 | 0.186 | 0.118 | +23% (no difference) |  |
| count_failed | 0.132 | 0.076 | 0.129 | 0.093 | +7% (no difference) |  |
| count_completed | 1.967 | 0.983 | 2.642 | 1.351 | +35% | SPREAD>2x mdb-main 100%; SPREAD>2x mdb-opt 96% |
| count_partition_single | 0.130 | 0.085 | 0.178 | 0.105 | +32% (no difference) |  |

### S2

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 2.055 | 1.178 | 0.982 | 0.620 | -50% | SPREAD>2x mdb-main 74%; SPREAD>2x mdb-opt 58% |
| keys_nonstrict | 6.029 | 3.093 | 1.097 | 0.637 | -81% | SPREAD>2x mdb-main 95%; SPREAD>2x mdb-opt 72% |
| pages | 0.645 | 0.381 | 0.505 | 0.319 | -20% | SPREAD>2x mdb-main 69%; SPREAD>2x mdb-opt 58% |
| insert | 0.596 | 0.503 | 0.540 | 0.381 | -16% (no difference) |  |
| upd_complete | 0.648 | 0.498 | 0.521 | 0.336 | -25% (no difference) |  |
| upd_retry | 0.300 | 0.264 | 0.325 | 0.246 | +1% (no difference) |  |
| upd_fail | 0.319 | 0.254 | 0.375 | 0.261 | +11% (no difference) |  |
| delete_by_id | 0.562 | 0.502 | 0.379 | 0.335 | -33% (no difference) |  |
| count_statuses | 3.727 | 1.808 | 4.902 | 2.379 | +32% | SPREAD>2x mdb-main 106%; SPREAD>2x mdb-opt 106% |
| count_partitions | 2.473 | 1.421 | 0.755 | 0.420 | -70% | SPREAD>2x mdb-main 74%; SPREAD>2x mdb-opt 80% |
| oldest_created | 2.605 | 1.472 | 0.950 | 0.529 | -64% | SPREAD>2x mdb-main 77%; SPREAD>2x mdb-opt 80% |
| oldest_due_strict | 6.751 | 3.762 | 0.685 | 0.396 | -90% | SPREAD>2x mdb-main 79%; SPREAD>2x mdb-opt 73% |
| oldest_due_nonstrict | 1.835 | 1.000 | 1.174 | 0.647 | -36% | SPREAD>2x mdb-main 84%; SPREAD>2x mdb-opt 81% |
| count_new | 0.360 | 0.184 | 0.365 | 0.196 | +3% (no difference) | SPREAD>2x mdb-main 0.176 ms; SPREAD>2x mdb-opt 0.169 ms |
| count_failed | 3.367 | 1.624 | 3.473 | 1.735 | +4% (no difference) | SPREAD>2x mdb-main 107%; SPREAD>2x mdb-opt 100% |
| count_completed | 185.9 | 91.606 | 199.7 | 101.3 | +8% (no difference) | SPREAD>2x mdb-main 103%; SPREAD>2x mdb-opt 97% |
| count_partition_single | 0.132 | 0.090 | 0.355 | 0.203 | +151% | SPREAD>2x mdb-opt 0.152 ms |

### S4

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 2.518 | 1.610 | over 60 s | over 60 s | opt over limit | SPREAD>2x mdb-main 56% |
| keys_nonstrict | 115028.7* | 96725.7* | 121029.9* | 96462.3* | +3% (no difference) | SPREAD>2x mdb-opt 25% |
| pages | 0.566 | 0.322 | over 60 s | over 60 s | opt over limit | SPREAD>2x mdb-main 76% |
| insert | 0.746 | 0.595 | 0.857 | 0.681 | +15% (no difference) |  |
| upd_complete | 0.906 | 0.752 | 0.977 | 0.806 | +8% (no difference) |  |
| upd_retry | 0.386 | 0.388 | 0.628 | 0.407 | +34% (no difference) |  |
| upd_fail | 0.258 | 0.285 | 0.459 | 0.356 | +50% (no difference) |  |
| delete_by_id | 0.713 | 0.527 | 0.621 | 0.551 | -5% (no difference) |  |
| count_statuses | 93.205 | 85.630 | 215.3 | 107.0 | +80% | SPREAD>2x mdb-opt 101% |
| count_partitions | 482.0 | 359.8 | 225.7 | 108.6 | -60% | SPREAD>2x mdb-main 34%; SPREAD>2x mdb-opt 108% |
| oldest_created | 97140.8* | 90724.3* | 87376.8* | 78675.7* | -12% |  |
| oldest_due_strict | 30211.9* | 21285.3 | over 61 s | over 61 s | opt over limit | SPREAD>2x mdb-main 42% |
| oldest_due_nonstrict | 97042.0* | 82450.3* | 83523.6* | 76161.5* | -11% |  |
| count_new | 93.073 | 85.545 | 100.0 | 92.584 | +8% (no difference) |  |
| count_failed | 0.132 | 0.085 | 0.116 | 0.080 | -10% (no difference) |  |
| count_completed | 184.6 | 94.723 | 109.2 | 101.9 | -24% | SPREAD>2x mdb-main 95% |
| count_partition_single | 0.951 | 0.497 | 170.8 | 85.578 | +17607% | SPREAD>2x mdb-main 91%; SPREAD>2x mdb-opt 100% |

### S4-b100

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 2.120 | 2.050 | over 60 s | over 60 s | opt over limit |  |
| keys_nonstrict | 107965.9* | 84484.7* | 123609.0* | 106426.6* | +20% | SPREAD>2x mdb-main 28% |
| pages | 1.421 | 1.373 | over 60 s | over 60 s | opt over limit |  |
| insert | 0.611 | 0.566 | 0.784 | 0.687 | +25% (no difference) |  |
| upd_complete | 0.731 | 0.782 | 0.803 | 0.818 | +7% (no difference) |  |
| upd_retry | 0.455 | 0.408 | 0.398 | 0.429 | -4% (no difference) |  |
| upd_fail | 0.311 | 0.303 | 0.318 | 0.337 | +7% (no difference) |  |
| delete_by_id | 0.591 | 0.592 | 0.562 | 0.535 | -7% (no difference) |  |
| count_statuses | 91.613 | 84.764 | 121.1 | 107.3 | +29% |  |
| count_partitions | 348.2 | 357.4 | 134.2 | 109.6 | -65% | SPREAD>2x mdb-opt 22% |
| oldest_created | 93710.3* | 79789.4* | 96329.2* | 86548.7* | +5% (no difference) |  |
| oldest_due_strict | 23607.9 | 19928.1 | over 60 s | over 60 s | opt over limit |  |
| oldest_due_nonstrict | 92932.5* | 412.6 | 95894.9* | 83542.8* | +92% | SPREAD>2x mdb-main 22423% |
| count_new | 91.534 | 84.696 | 106.5 | 93.340 | +13% |  |
| count_failed | 0.079 | 0.068 | 0.114 | 0.081 | +33% (no difference) |  |
| count_completed | 97.265 | 93.306 | 104.9 | 102.6 | +9% (no difference) |  |
| count_partition_single | 0.501 | 0.483 | 99.397 | 85.992 | +18740% |  |

### S5

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 4.691 | 4.665 | 16085.7 | 14901.4 | +331100% |  |
| keys_nonstrict | 14407.7 | 13313.9 | 18186.5 | 15662.4 | +22% |  |
| pages | 4648.2 | 4265.2 | 1582.5 | 1369.8 | -67% |  |
| insert | 0.512 | 0.440 | 0.394 | 0.339 | -23% (no difference) |  |
| upd_complete | 0.797 | 0.765 | 0.484 | 0.412 | -43% (no difference) |  |
| upd_retry | 0.317 | 0.313 | 0.323 | 0.250 | -9% (no difference) |  |
| upd_fail | 0.242 | 0.245 | 0.232 | 0.150 | -22% (no difference) |  |
| delete_by_id | 0.532 | 0.538 | 0.387 | 0.414 | -25% (no difference) |  |
| count_statuses | 17.354 | 17.542 | 22.003 | 21.779 | +25% |  |
| count_partitions | 13280.9 | 13121.6 | 21.927 | 22.342 | -100% |  |
| oldest_created | 3228.9 | 2825.1 | 15156.5 | 14618.8 | +392% |  |
| oldest_due_strict | 3723.8 | 3427.9 | 15548.9 | 14157.5 | +315% |  |
| oldest_due_nonstrict | 38.008 | 37.302 | 14884.6 | 13389.6 | +37444% |  |
| count_new | 17.281 | 17.452 | 18.803 | 18.768 | +8% (no difference) |  |
| count_failed | 0.073 | 0.090 | 0.092 | 0.090 | +12% (no difference) |  |
| count_completed | 28.043 | 28.559 | 30.661 | 30.848 | +9% (no difference) |  |
| count_partition_single | 0.281 | 0.297 | 17.195 | 17.150 | +5842% |  |

### S6

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 417.5 | 385.0 | 16196.5 | 15594.0 | +3862% |  |
| keys_nonstrict | 405.1 | 372.5 | 16034.1 | 15358.0 | +3937% |  |
| pages | 0.359 | 0.355 | 0.312 | 0.308 | -13% |  |
| insert | 0.430 | 0.488 | 0.560 | 0.422 | +7% (no difference) |  |
| upd_complete | 0.385 | 0.484 | 0.407 | 0.423 | -4% (no difference) |  |
| upd_retry | 0.187 | 0.256 | 0.385 | 0.353 | +67% (no difference) |  |
| upd_fail | 0.163 | 0.232 | 0.341 | 0.257 | +51% (no difference) |  |
| delete_by_id | 0.462 | 0.491 | 0.553 | 0.477 | +8% (no difference) |  |
| count_statuses | 17.742 | 17.686 | 23.045 | 21.910 | +27% |  |
| count_partitions | 24.665 | 24.788 | 23.151 | 22.489 | -8% (no difference) |  |
| oldest_created | 7516.2 | 7071.5 | 16978.9 | 16154.5 | +127% |  |
| oldest_due_strict | 442.6 | 383.3 | 17574.9 | 15469.1 | +3901% |  |
| oldest_due_nonstrict | 1.772 | 1.765 | 17511.0 | 14955.0 | +917795% |  |
| count_new | 17.672 | 17.603 | 20.236 | 18.814 | +11% |  |
| count_failed | 0.070 | 0.083 | 0.075 | 0.084 | +4% (no difference) |  |
| count_completed | 74.889 | 75.868 | 85.874 | 81.781 | +11% |  |
| count_partition_single | 0.187 | 0.180 | 18.112 | 16.982 | +9462% |  |

### S7

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 1.234 | 1.242 | 837.2 | 740.3 | +63609% |  |
| keys_nonstrict | 885.0 | 745.8 | 850.5 | 753.7 | -2% (no difference) |  |
| pages | 0.350 | 0.342 | 0.305 | 0.327 | -9% (no difference) |  |
| insert | 0.443 | 0.500 | 0.371 | 0.381 | -20% (no difference) |  |
| upd_complete | 0.751 | 0.669 | 0.532 | 0.436 | -32% (no difference) |  |
| upd_retry | 0.272 | 0.334 | 0.253 | 0.250 | -17% (no difference) |  |
| upd_fail | 0.249 | 0.285 | 0.286 | 0.294 | +9% (no difference) |  |
| delete_by_id | 0.487 | 0.464 | 0.442 | 0.378 | -14% (no difference) |  |
| count_statuses | 49.552 | 49.257 | 70.365 | 69.500 | +42% |  |
| count_partitions | 818.8 | 788.5 | 1.478 | 1.442 | -100% |  |
| oldest_created | 818.4 | 797.0 | 799.4 | 768.5 | -3% (no difference) |  |
| oldest_due_strict | 852.7 | 827.5 | 828.8 | 778.7 | -4% (no difference) |  |
| oldest_due_nonstrict | 2.761 | 2.711 | 790.4 | 750.9 | +28067% |  |
| count_new | 0.992 | 0.960 | 1.098 | 1.020 | +9% (no difference) |  |
| count_failed | 48.560 | 48.297 | 55.493 | 54.189 | +13% |  |
| count_completed | 56.472 | 57.202 | 64.567 | 61.791 | +11% |  |
| count_partition_single | 0.086 | 0.086 | 1.001 | 0.941 | +1029% |  |

### S9

| query | main p1 | main p2 | opt p1 | opt p2 | change (opt vs main) | flags |
|---|---|---|---|---|---|---|
| keys_strict | 1.231 | - | 0.719 | - | -42% |  |
| keys_nonstrict | 3.210 | - | 0.684 | - | -79% |  |
| pages | 0.362 | - | 0.358 | - | -1% (no difference) |  |
| insert | 0.341 | - | 0.408 | - | +20% (no difference) |  |
| upd_complete | 0.358 | - | 0.643 | - | +80% |  |
| upd_retry | 0.128 | - | 0.275 | - | +115% (no difference) |  |
| upd_fail | 0.144 | - | 0.502 | - | +249% |  |
| delete_by_id | 1.054 | - | 0.757 | - | -28% (no difference) |  |
| count_statuses | 3.101 | - | 4.046 | - | +30% |  |
| count_partitions | 1.459 | - | 0.420 | - | -71% |  |
| oldest_created | 1.492 | - | 0.586 | - | -61% |  |
| oldest_due_strict | 3.802 | - | 0.479 | - | -87% |  |
| oldest_due_nonstrict | 1.055 | - | 0.714 | - | -32% |  |
| count_new | 0.193 | - | 0.195 | - | +1% (no difference) |  |
| count_failed | 2.908 | - | 3.093 | - | +6% (no difference) |  |
| count_completed | 125.4 | - | 89.366 | - | -29% |  |
| count_partition_single | 0.106 | - | 0.201 | - | +90% |  |

## 1b. Rerun of pass-1 cells with pass-1 vs pass-2 spread > 2x threshold (`results/cmp/rerun1/`)

Pass 1 of S1, S2, S16-S2, S4, S16-S4, S4-b100, S7 (and S8-S7) ran partly under outside host load 11-15 while pass 2 ran at load 1-3, which produced pass-to-pass spreads of 25-100 %. Each of these cells was rerun once (variant order mdb-main, mdb-opt, same snapshot, same limits) on a quiet host (loadavg 1-2). Columns: rerun (r1), pass 1 (p1), pass 2 (p2). Change = on mean(rerun, pass 2); flags = spread rerun vs pass 2.

### S8-S7 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.553 | 0.557 | 0.555 | 0.451 | 0.463 | 0.449 | -19% |  |
| keys_nonstrict | 5.848 | 5.973 | 5.884 | 0.486 | 0.616 | 0.488 | -92% |  |
| pages | 0.352 | 0.354 | 0.356 | 0.321 | 0.337 | 0.312 | -11% (no difference) |  |
| insert | 0.458 | 0.502 | 0.537 | 0.419 | 0.454 | 0.434 | -14% (no difference) |  |
| upd_complete | 0.732 | 0.784 | 0.807 | 0.481 | 0.501 | 0.369 | -45% (no difference) |  |
| upd_retry | 0.345 | 0.333 | 0.342 | 0.217 | 0.287 | 0.145 | -47% (no difference) |  |
| upd_fail | 0.454 | 0.305 | 0.292 | 0.293 | 0.362 | 0.214 | -32% (no difference) |  |
| delete_by_id | 0.354 | 0.361 | 0.367 | 0.304 | 0.402 | 0.336 | -11% (no difference) |  |
| count_statuses | 48.677 | 48.598 | 49.269 | 71.433 | 69.117 | 69.421 | +44% |  |
| count_partitions | 0.566 | 0.587 | 0.560 | 1.167 | 1.121 | 1.171 | +108% |  |
| oldest_created | 3.111 | 3.053 | 3.061 | 1.949 | 1.848 | 1.869 | -38% |  |
| oldest_due_strict | 7.792 | 7.984 | 7.875 | 0.311 | 0.306 | 0.318 | -96% |  |
| oldest_due_nonstrict | 0.772 | 0.826 | 0.750 | 1.910 | 1.911 | 1.929 | +152% |  |

### S16-S2 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.594 | 1.045 | 0.617 | 0.605 | 1.009 | 0.616 | +1% (no difference) |  |
| keys_nonstrict | 0.393 | 0.650 | 0.392 | 0.619 | 1.040 | 0.626 | +59% |  |
| pages | 0.377 | 0.629 | 0.377 | 0.317 | 0.545 | 0.316 | -16% |  |
| insert | 0.632 | 0.942 | 0.631 | 0.433 | 0.605 | 0.356 | -38% (no difference) |  |
| upd_complete | 0.417 | 0.614 | 0.420 | 0.310 | 0.468 | 0.228 | -36% (no difference) |  |
| upd_retry | 0.254 | 0.350 | 0.256 | 0.328 | 0.349 | 0.155 | -5% (no difference) |  |
| upd_fail | 0.268 | 0.392 | 0.202 | 0.266 | 0.313 | 0.183 | -4% (no difference) |  |
| delete_by_id | 0.407 | 0.462 | 0.332 | 0.324 | 0.392 | 0.316 | -13% (no difference) |  |
| count_statuses | 1.834 | 3.736 | 1.845 | 2.386 | 4.746 | 2.468 | +32% |  |
| count_partitions | 0.136 | 0.231 | 0.149 | 0.260 | 0.448 | 0.227 | +71% |  |
| oldest_created | 0.199 | 0.343 | 0.216 | 0.214 | 0.346 | 0.220 | +5% (no difference) |  |
| oldest_due_strict | 0.431 | 0.726 | 0.438 | 0.475 | 0.786 | 0.477 | +10% (no difference) |  |
| oldest_due_nonstrict | 0.177 | 0.286 | 0.182 | 0.254 | 0.417 | 0.257 | +42% |  |

### S16-S4 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.535 | 0.542 | 0.525 | 0.439 | 0.426 | 0.441 | -17% |  |
| keys_nonstrict | 5111.7 | 6604.5 | 5135.6 | 0.465 | 0.466 | 0.468 | -100% |  |
| pages | 0.319 | 0.328 | 0.311 | 0.312 | 0.307 | 0.330 | +2% (no difference) |  |
| insert | 0.646 | 0.676 | 0.647 | 0.501 | 0.479 | 0.478 | -24% (no difference) |  |
| upd_complete | 0.770 | 0.783 | 0.740 | 0.755 | 0.745 | 0.746 | -1% (no difference) |  |
| upd_retry | 0.320 | 0.328 | 0.324 | 0.409 | 0.399 | 0.404 | +26% (no difference) |  |
| upd_fail | 0.301 | 0.287 | 0.280 | 0.324 | 0.174 | 0.178 | -14% (no difference) |  |
| delete_by_id | 0.450 | 0.457 | 0.481 | 0.444 | 0.564 | 0.488 | +0% (no difference) |  |
| count_statuses | 85.001 | 86.734 | 85.528 | 106.8 | 108.2 | 107.1 | +25% |  |
| count_partitions | 7.459 | 7.569 | 7.476 | 93.000 | 94.115 | 94.244 | +1154% |  |
| oldest_created | 4826.8 | 5640.4 | 5430.2 | 5164.2 | 5298.0 | 5130.0 | +0% (no difference) |  |
| oldest_due_strict | 7376.2 | 8074.8 | 7839.0 | 0.308 | 0.298 | 0.299 | -100% |  |
| oldest_due_nonstrict | 11.286 | 11.552 | 11.477 | 5088.9 | 5293.3 | 4810.9 | +43390% |  |

### S1 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.945 | 1.621 | 0.952 | 0.601 | 1.044 | 0.602 | -37% |  |
| keys_nonstrict | 1.100 | 1.918 | 1.110 | 0.628 | 1.030 | 0.619 | -44% |  |
| pages | 0.398 | 0.677 | 0.382 | 0.296 | 0.564 | 0.302 | -23% |  |
| insert | 0.297 | 0.325 | 0.214 | 0.179 | 0.295 | 0.272 | -12% (no difference) |  |
| upd_complete | 0.313 | 0.294 | 0.176 | 0.173 | 0.286 | 0.249 | -14% (no difference) |  |
| upd_retry | 0.234 | 0.223 | 0.140 | 0.143 | 0.212 | 0.130 | -27% (no difference) |  |
| upd_fail | 0.223 | 0.221 | 0.148 | 0.144 | 0.219 | 0.171 | -15% (no difference) |  |
| delete_by_id | 0.081 | 0.118 | 0.079 | 0.075 | 0.123 | 0.074 | -7% (no difference) |  |
| count_statuses | 0.188 | 0.288 | 0.168 | 0.115 | 0.199 | 0.129 | -31% |  |
| count_partitions | 0.362 | 0.625 | 0.358 | 0.296 | 0.511 | 0.299 | -17% |  |
| oldest_created | 0.368 | 0.631 | 0.366 | 0.324 | 0.532 | 0.311 | -13% |  |
| oldest_due_strict | 1.043 | 1.786 | 1.039 | 0.412 | 0.679 | 0.412 | -60% |  |
| oldest_due_nonstrict | 0.551 | 0.952 | 0.546 | 0.486 | 0.776 | 0.466 | -13% |  |

### S2 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.189 | 2.055 | 1.178 | 0.602 | 0.982 | 0.620 | -48% |  |
| keys_nonstrict | 3.128 | 6.029 | 3.093 | 0.637 | 1.097 | 0.637 | -80% |  |
| pages | 0.373 | 0.645 | 0.381 | 0.325 | 0.505 | 0.319 | -15% |  |
| insert | 0.423 | 0.596 | 0.503 | 0.383 | 0.540 | 0.381 | -17% (no difference) |  |
| upd_complete | 0.377 | 0.648 | 0.498 | 0.346 | 0.521 | 0.336 | -22% (no difference) |  |
| upd_retry | 0.167 | 0.300 | 0.264 | 0.236 | 0.325 | 0.246 | +12% (no difference) |  |
| upd_fail | 0.170 | 0.319 | 0.254 | 0.179 | 0.375 | 0.261 | +4% (no difference) |  |
| delete_by_id | 0.450 | 0.562 | 0.502 | 0.324 | 0.379 | 0.335 | -31% (no difference) |  |
| count_statuses | 1.815 | 3.727 | 1.808 | 2.373 | 4.902 | 2.379 | +31% |  |
| count_partitions | 1.511 | 2.473 | 1.421 | 0.416 | 0.755 | 0.420 | -71% |  |
| oldest_created | 1.510 | 2.605 | 1.472 | 0.533 | 0.950 | 0.529 | -64% |  |
| oldest_due_strict | 3.763 | 6.751 | 3.762 | 0.401 | 0.685 | 0.396 | -89% |  |
| oldest_due_nonstrict | 1.006 | 1.835 | 1.000 | 0.651 | 1.174 | 0.647 | -35% |  |

### S4 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.583 | 2.518 | 1.610 | over 60 s | over 60 s | over 60 s | opt over limit |  |
| keys_nonstrict | 85037.3* | 115028.7* | 96725.7* | 97344.1* | 121029.9* | 96462.3* | +7% (no difference) |  |
| pages | 0.331 | 0.566 | 0.322 | over 60 s | over 60 s | over 60 s | opt over limit |  |
| insert | 0.547 | 0.746 | 0.595 | 0.673 | 0.857 | 0.681 | +19% (no difference) |  |
| upd_complete | 0.677 | 0.906 | 0.752 | 0.744 | 0.977 | 0.806 | +8% (no difference) |  |
| upd_retry | 0.292 | 0.386 | 0.388 | 0.431 | 0.628 | 0.407 | +23% (no difference) |  |
| upd_fail | 0.176 | 0.258 | 0.285 | 0.336 | 0.459 | 0.356 | +50% (no difference) |  |
| delete_by_id | 0.521 | 0.713 | 0.527 | 0.567 | 0.621 | 0.551 | +7% (no difference) |  |
| count_statuses | 85.301 | 93.205 | 85.630 | 106.8 | 215.3 | 107.0 | +25% |  |
| count_partitions | 358.5 | 482.0 | 359.8 | 115.1 | 225.7 | 108.6 | -69% |  |
| oldest_created | 79278.5* | 97140.8* | 90724.3* | 92646.3* | 87376.8* | 78675.7* | +1% (no difference) |  |
| oldest_due_strict | 19974.2 | 30211.9* | 21285.3 | over 61 s | over 61 s | over 61 s | opt over limit |  |
| oldest_due_nonstrict | 75280.7* | 97042.0* | 82450.3* | 87134.4* | 83523.6* | 76161.5* | +4% (no difference) |  |

### S4-b100 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 2.032 | 2.120 | 2.050 | over 60 s | over 60 s | over 60 s | opt over limit |  |
| keys_nonstrict | 84852.6* | 107965.9* | 84484.7* | 105830.1* | 123609.0* | 106426.6* | +25% |  |
| pages | 1.460 | 1.421 | 1.373 | over 60 s | over 60 s | over 60 s | opt over limit |  |
| insert | 0.580 | 0.611 | 0.566 | 0.698 | 0.784 | 0.687 | +21% (no difference) |  |
| upd_complete | 0.752 | 0.731 | 0.782 | 0.804 | 0.803 | 0.818 | +6% (no difference) |  |
| upd_retry | 0.395 | 0.455 | 0.408 | 0.428 | 0.398 | 0.429 | +7% (no difference) |  |
| upd_fail | 0.263 | 0.311 | 0.303 | 0.345 | 0.318 | 0.337 | +20% (no difference) |  |
| delete_by_id | 0.567 | 0.591 | 0.592 | 0.570 | 0.562 | 0.535 | -5% (no difference) |  |
| count_statuses | 84.885 | 91.613 | 84.764 | 107.3 | 121.1 | 107.3 | +26% |  |
| count_partitions | 373.5 | 348.2 | 357.4 | 109.6 | 134.2 | 109.6 | -70% |  |
| oldest_created | 79233.3* | 93710.3* | 79789.4* | 86653.1* | 96329.2* | 86548.7* | +9% (no difference) |  |
| oldest_due_strict | 19893.3 | 23607.9 | 19928.1 | over 60 s | over 60 s | over 60 s | opt over limit |  |
| oldest_due_nonstrict | 75472.6* | 92932.5* | 412.6 | 84101.3* | 95894.9* | 83542.8* | +121% | SPREAD>2x mdb-main 18191% |

### S7 (rerun)

| query | main r1 | main p1 | main p2 | opt r1 | opt p1 | opt p2 | change (mean r1,p2) | flags (r1 vs p2) |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.213 | 1.234 | 1.242 | 773.6 | 837.2 | 740.3 | +61565% |  |
| keys_nonstrict | 758.0 | 885.0 | 745.8 | 775.0 | 850.5 | 753.7 | +2% (no difference) |  |
| pages | 0.331 | 0.350 | 0.342 | 0.309 | 0.305 | 0.327 | -5% (no difference) |  |
| insert | 0.449 | 0.443 | 0.500 | 0.408 | 0.371 | 0.381 | -17% (no difference) |  |
| upd_complete | 0.663 | 0.751 | 0.669 | 0.459 | 0.532 | 0.436 | -33% (no difference) |  |
| upd_retry | 0.327 | 0.272 | 0.334 | 0.257 | 0.253 | 0.250 | -23% (no difference) |  |
| upd_fail | 0.284 | 0.249 | 0.285 | 0.327 | 0.286 | 0.294 | +9% (no difference) |  |
| delete_by_id | 0.471 | 0.487 | 0.464 | 0.456 | 0.442 | 0.378 | -11% (no difference) |  |
| count_statuses | 48.178 | 49.552 | 49.257 | 69.056 | 70.365 | 69.500 | +42% |  |
| count_partitions | 890.4 | 818.8 | 788.5 | 1.388 | 1.478 | 1.442 | -100% |  |
| oldest_created | 883.6 | 818.4 | 797.0 | 776.1 | 799.4 | 768.5 | -8% (no difference) |  |
| oldest_due_strict | 914.7 | 852.7 | 827.5 | 804.8 | 828.8 | 778.7 | -9% (no difference) |  |
| oldest_due_nonstrict | 2.717 | 2.761 | 2.711 | 774.3 | 790.4 | 750.9 | +27999% |  |

## 2. Plan shape (pass 1) of queries > 5 ms in either variant, and of branch regressions

### S8-S2

**count_statuses** (main p1 3.622, opt p1 4.702, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1200 r_rows=1200 examined=1200 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1200   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=26586 r_rows=12800 examined=12800 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 12800   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=27120 r_rows=14000 examined=14000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 14000   rows_returned: 2
```

**oldest_due_nonstrict** (main p1 0.586, opt p1 0.871, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=233 r_rows=229 examined=229 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 229   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=1000 r_rows=1000 examined=1000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000   rows_returned: 1
```

### S8-S4

**keys_nonstrict** (main p1 27557.2, opt p1 0.922)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=507930 r_rows=250815 examined=250815 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 250815   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=1594969 r_rows=50 examined=50 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10 est=1 r_rows=0 examined=10 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 60   rows_returned: 10
```

**upd_retry** (main p1 0.342, opt p1 0.588, branch regression)

mdb-main:
```
id: upd_retry   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```
mdb-opt:
```
id: upd_retry   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```

**count_statuses** (main p1 175.8, opt p1 218.1, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1756077 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1594969 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**count_partitions** (main p1 61.279, opt p1 198.0, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=509106 r_rows=250815 examined=250815 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 250815   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1594969 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**oldest_created** (main p1 15214.2, opt p1 296.6)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ALL key=- parts=[-] loops=1 est=1756077 r_rows=2000000 examined=2000000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 2000000   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=1594969 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**oldest_due_strict** (main p1 21951.6, opt p1 0.604)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: ALL key=- parts=[-] loops=1 est=1756077 r_rows=2000000 examined=2000000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=250815 est=1 r_rows=1 examined=250815 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 2250815   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=1594969 r_rows=8 examined=8 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=1 examined=1 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 9   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 90.860, opt p1 278.1, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=509106 r_rows=250815 examined=250815 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 250815   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=1594969 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

### S8-S5

**keys_strict** (main p1 14919.0, opt p1 197.1)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 9 returned
  o: range key=idx_outbox_record_status_created parts=[status] loops=1 est=398905 r_rows=199980 examined=199980 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=33992 est=1 r_rows=1.529 examined=51983.000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 251963.000   rows_returned: 9
```
mdb-opt:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: 10 | 9 returned
  o: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=408356 r_rows=199980 examined=199980 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=33992 est=1 r_rows=1.529 examined=51983.000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 251963.000   rows_returned: 9
```

**keys_nonstrict** (main p1 2440.6, opt p1 8.829)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=38096 r_rows=33992 examined=33992 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 33992   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=408356 r_rows=126 examined=126 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=20 est=1 r_rows=1 examined=40000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 40126   rows_returned: 10
```

**pages** (main p1 5080.1, opt p1 1491.0)

mdb-main:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 18000 returned
  outbox_record: ALL key=- parts=[-] loops=1 est=398905 r_rows=500000 examined=500000 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 500000   rows_returned: 18000
```
mdb-opt:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 18000 returned
  outbox_record: range key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=36004 r_rows=18000 examined=18000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 18000   rows_returned: 18000
```

**count_statuses** (main p1 35.729, opt p1 44.291, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=383994 r_rows=199980 examined=199980 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20 r_rows=20 examined=20 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 20   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=380918 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 2
```

**count_partitions** (main p1 8.683, opt p1 39.551, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=38101 r_rows=33997 examined=33997 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 33997   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=380898 r_rows=199980 examined=199980 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```

**oldest_created** (main p1 2440.4, opt p1 58.160)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=38101 r_rows=33997 examined=33997 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 33997   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=408356 r_rows=199980 examined=199980 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```

**oldest_due_strict** (main p1 2605.6, opt p1 1.064)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=38096 r_rows=33992 examined=33992 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=33992 est=1 r_rows=1.529 examined=51983.000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 85975.000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=410718 r_rows=3 examined=3 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=2000 examined=2000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 2003   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 12.825, opt p1 56.601, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=38096 r_rows=33992 examined=33992 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 33992   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=410718 r_rows=199960 examined=199960 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199960   rows_returned: 1
```

### S8-S6

**count_statuses** (main p1 17.653, opt p1 23.161, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=379940 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=386643 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**count_partitions** (main p1 6.849, opt p1 22.797, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=50229 r_rows=50229 examined=50229 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 50229   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=386642 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**oldest_created** (main p1 4301.7, opt p1 34.353)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=50229 r_rows=50229 examined=50229 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 50229   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=436708 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 0.546, opt p1 1.196, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=1258 r_rows=1258 examined=1258 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1258   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=10082 r_rows=5000 examined=5000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 5000   rows_returned: 1
```

### S8-S7

**keys_nonstrict** (main p1 5.973, opt p1 0.616)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=2510 r_rows=2510 examined=2510 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 2510   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=21804 r_rows=30 examined=30 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10 est=1 r_rows=0 examined=10 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 40   rows_returned: 10
```

**count_statuses** (main p1 48.598, opt p1 69.117, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20412 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=814902 r_rows=390000 examined=390000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 390000   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_retry parts=[status] loops=1 est=770310 r_rows=400000 examined=400000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 400000   rows_returned: 2
```

**count_partitions** (main p1 0.587, opt p1 1.121, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=2510 r_rows=2510 examined=2510 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 2510   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=20834 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```

**oldest_due_strict** (main p1 7.984, opt p1 0.306)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=2510 r_rows=2510 examined=2510 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=2510 est=1 r_rows=1 examined=2510 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 5020   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=21090 r_rows=3 examined=3 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=1 examined=1 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 4   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 0.826, opt p1 1.911, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=2510 r_rows=2510 examined=2510 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 2510   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=21090 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```

### S16-S2

**keys_nonstrict** (main p1 0.650, opt p1 1.040, branch regression)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=53 r_rows=53 examined=53 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 53   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=62 r_rows=62 examined=62 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=53 est=1 r_rows=0.019 examined=54.000 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 116.000   rows_returned: 10
```

**count_statuses** (main p1 3.736, opt p1 4.746, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1200 r_rows=1200 examined=1200 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1200   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=26586 r_rows=12800 examined=12800 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 12800   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=27120 r_rows=14000 examined=14000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 14000   rows_returned: 2
```

**count_partitions** (main p1 0.231, opt p1 0.448, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=62 r_rows=62 examined=62 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_retry parts=[status] loops=1 est=1200 r_rows=1200 examined=1200 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1200   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 0.286, opt p1 0.417, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=53 r_rows=53 examined=53 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 53   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=62 r_rows=62 examined=62 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62   rows_returned: 1
```

### S16-S4

**keys_nonstrict** (main p1 6604.5, opt p1 0.466)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=126528 r_rows=62536 examined=62536 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=1675523 r_rows=169 examined=169 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10 est=1 r_rows=0 examined=10 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 179   rows_returned: 10
```

**count_statuses** (main p1 86.734, opt p1 108.2, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1884963 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1675523 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**count_partitions** (main p1 7.569, opt p1 94.115, branch regression)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=126816 r_rows=62536 examined=62536 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1675523 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**oldest_created** (main p1 5640.4, opt p1 5298.0)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=126816 r_rows=62536 examined=62536 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=126186 r_rows=62536 examined=62536 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 1
```

**oldest_due_strict** (main p1 8074.8, opt p1 0.298)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=126816 r_rows=62536 examined=62536 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=62536 est=1 r_rows=1 examined=62536 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 125072   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=1675523 r_rows=15 examined=15 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=1 examined=1 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 16   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 11.552, opt p1 5293.3, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=126816 r_rows=62536 examined=62536 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=126186 r_rows=62536 examined=62536 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 62536   rows_returned: 1
```

### S2

**keys_nonstrict** (main p1 6.029, opt p1 1.097)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_status_retry parts=[status,next_retry_at] loops=1 est=1000 r_rows=1000 examined=1000 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 1000   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: ref key=idx_outbox_record_status_completed_created_id parts=[status,completed_at] loops=1 est=1200 r_rows=12 examined=12 covering=yes
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10 est=1 r_rows=0 examined=10 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 22   rows_returned: 10
```

**count_statuses** (main p1 3.727, opt p1 4.902, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1200 r_rows=1200 examined=1200 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1200   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=26586 r_rows=12800 examined=12800 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 12800   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=27120 r_rows=14000 examined=14000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 14000   rows_returned: 2
```

**oldest_due_strict** (main p1 6.751, opt p1 0.685)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_status_retry parts=[status,next_retry_at] loops=1 est=1000 r_rows=1000 examined=1000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=1000 est=1 r_rows=1 examined=1000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 2000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_retry parts=[status,completed_at,next_retry_at,partition_no] loops=1 est=1000 r_rows=1 examined=1 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=1 examined=1 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 2   rows_returned: 1
```

### S4

**keys_strict** (main p1 2.518, opt p1 over 60 s, branch regression)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_status_created parts=[status] loops=1 est=1772188 r_rows=10 examined=10 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=10 est=1 r_rows=1 examined=10 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20   rows_returned: 10
```
mdb-opt:
```
id: keys_strict   mode: EXPLAIN (not executed)   sql_select_limit: 10 | n/a returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**keys_nonstrict** (main p1 115028.7*, opt p1 121029.9*)

mdb-main:
```
id: keys_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_status_retry parts=[status,next_retry_at] loops=1 est=1772188 r_rows=- examined=- covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: 10
```

**pages** (main p1 0.566, opt p1 over 60 s, branch regression)

mdb-main:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  outbox_record: range key=idx_outbox_record_record_key_created parts=[record_key] loops=1 est=10 r_rows=10 examined=10 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10   rows_returned: 10
```
mdb-opt:
```
id: pages   mode: EXPLAIN (not executed)   sql_select_limit: none | n/a returned
  outbox_record: range key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=10 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**upd_retry** (main p1 0.386, opt p1 0.628, branch regression)

mdb-main:
```
id: upd_retry   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```
mdb-opt:
```
id: upd_retry   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```

**upd_fail** (main p1 0.258, opt p1 0.459, branch regression)

mdb-main:
```
id: upd_fail   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```
mdb-opt:
```
id: upd_fail   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```

**count_statuses** (main p1 93.205, opt p1 215.3, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1772188 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1828575 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**count_partitions** (main p1 482.0, opt p1 225.7)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=67328 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=1828575 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**oldest_created** (main p1 97140.8*, opt p1 87376.8*)

mdb-main:
```
id: oldest_created   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=67328 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```

**oldest_due_strict** (main p1 30211.9*, opt p1 over 61 s, branch regression)

mdb-main:
```
id: oldest_due_strict   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  o: ALL key=- parts=[-] loops=1 est=1772188 r_rows=- examined=- covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: EXPLAIN (not executed)   sql_select_limit: 1 | n/a returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**oldest_due_nonstrict** (main p1 97042.0*, opt p1 83523.6*)

mdb-main:
```
id: oldest_due_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  o: ref key=idx_outbox_record_status_created parts=[status] loops=1 est=1772188 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```

### S4-b100

**keys_strict** (main p1 2.120, opt p1 over 60 s, branch regression)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 100 returned
  o: range key=idx_outbox_record_status_created parts=[status] loops=1 est=1739966 r_rows=100 examined=100 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=100 est=1 r_rows=1 examined=100 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 200   rows_returned: 100
```
mdb-opt:
```
id: keys_strict   mode: EXPLAIN (not executed)   sql_select_limit: 100 | n/a returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**keys_nonstrict** (main p1 107965.9*, opt p1 123609.0*, branch regression)

mdb-main:
```
id: keys_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: none | 100 returned
  o: range key=idx_outbox_record_status_retry parts=[status,next_retry_at] loops=1 est=1739966 r_rows=- examined=- covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 100
```
mdb-opt:
```
id: keys_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: 100 | 100 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: 100
```

**pages** (main p1 1.421, opt p1 over 60 s, branch regression)

mdb-main:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 100 returned
  outbox_record: range key=idx_outbox_record_record_key_created parts=[record_key] loops=1 est=100 r_rows=100 examined=100 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 100   rows_returned: 100
```
mdb-opt:
```
id: pages   mode: EXPLAIN (not executed)   sql_select_limit: none | n/a returned
  outbox_record: range key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=100 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**count_statuses** (main p1 91.613, opt p1 121.1, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1739966 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_retry parts=[status] loops=1 est=1926443 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**count_partitions** (main p1 348.2, opt p1 134.2)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=66048 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_retry parts=[status] loops=1 est=1926442 r_rows=1000000 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```

**oldest_created** (main p1 93710.3*, opt p1 96329.2*)

mdb-main:
```
id: oldest_created   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=66048 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```

**oldest_due_strict** (main p1 23607.9, opt p1 over 60 s, branch regression)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: ALL key=- parts=[-] loops=1 est=1739966 r_rows=2000000 examined=2000000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=1000000 est=1 r_rows=1 examined=1000000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 3000000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: EXPLAIN (not executed)   sql_select_limit: 1 | n/a returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=1 r_rows=- examined=- covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: n/a (not executed)   rows_returned: n/a
```

**oldest_due_nonstrict** (main p1 92932.5*, opt p1 95894.9*)

mdb-main:
```
id: oldest_due_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: none | 1 returned
  o: ref key=idx_outbox_record_status_created parts=[status] loops=1 est=1739966 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: EXPLAIN (not executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=- examined=- covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: n/a (not executed)   rows_returned: 1
```

### S5

**keys_strict** (main p1 4.691, opt p1 16085.7, branch regression)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_status_created parts=[status] loops=1 est=423204 r_rows=16 examined=16 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=14 est=1 r_rows=1428.857 examined=20004.000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20020.000   rows_returned: 10
```
mdb-opt:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=199980 examined=199980 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=199960 est=1 r_rows=1.610 examined=321899.000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 521879.000   rows_returned: 10
```

**keys_nonstrict** (main p1 14407.7, opt p1 18186.5, branch regression)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=260202 r_rows=199960 examined=199960 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 199960   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=199980 examined=199980 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=199960 est=1 r_rows=1.389 examined=3113599.999 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 3313579.999   rows_returned: 10
```

**pages** (main p1 4648.2, opt p1 1582.5)

mdb-main:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 20000 returned
  outbox_record: ALL key=- parts=[-] loops=1 est=423204 r_rows=500000 examined=500000 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 500000   rows_returned: 20000
```
mdb-opt:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none | 20000 returned
  outbox_record: range key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=1 est=40012 r_rows=20000 examined=20000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 20000   rows_returned: 20000
```

**count_statuses** (main p1 17.354, opt p1 22.003, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=383994 r_rows=199980 examined=199980 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20 r_rows=20 examined=20 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 20   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=380918 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 2
```

**count_partitions** (main p1 13280.9, opt p1 21.927)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=383994 r_rows=199980 examined=199980 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=380898 r_rows=199980 examined=199980 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```

**oldest_created** (main p1 3228.9, opt p1 15156.5, branch regression)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ALL key=- parts=[-] loops=1 est=423204 r_rows=500000 examined=500000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 500000   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=199980 examined=199980 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```

**oldest_due_strict** (main p1 3723.8, opt p1 15548.9, branch regression)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: ALL key=- parts=[-] loops=1 est=423204 r_rows=500000 examined=500000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=199960 est=1 r_rows=1.610 examined=321899.000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 821899.000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=199980 examined=199980 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=199960 est=1 r_rows=1.610 examined=321899.000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 521879.000   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 38.008, opt p1 14884.6, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=260806 r_rows=199960 examined=199960 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199960   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=199980 examined=199980 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 199980   rows_returned: 1
```

### S6

**keys_strict** (main p1 417.5, opt p1 16196.5, branch regression)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=5000 r_rows=5000 examined=5000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=5000 est=1 r_rows=1 examined=5000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 10000   rows_returned: 10
```
mdb-opt:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=200000 examined=200000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=5000 est=1 r_rows=1 examined=5000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 205000   rows_returned: 10
```

**keys_nonstrict** (main p1 405.1, opt p1 16034.1, branch regression)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=5000 r_rows=5000 examined=5000 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 5000   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=200000 examined=200000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=5000 est=1 r_rows=0 examined=5000 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 205000   rows_returned: 10
```

**count_statuses** (main p1 17.742, opt p1 23.045, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=379940 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1 r_rows=0 examined=0 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 0   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=386643 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**count_partitions** (main p1 24.665, opt p1 23.151)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status] loops=1 est=459264 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=386642 r_rows=200000 examined=200000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**oldest_created** (main p1 7516.2, opt p1 16978.9, branch regression)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ALL key=- parts=[-] loops=1 est=874130 r_rows=1000000 examined=1000000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1000000   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=200000 examined=200000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

**oldest_due_strict** (main p1 442.6, opt p1 17574.9, branch regression)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=5000 r_rows=5000 examined=5000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=5000 est=1 r_rows=1 examined=5000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 10000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=200000 examined=200000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=5000 est=1 r_rows=1 examined=5000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 205000   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 1.772, opt p1 17511.0, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=5000 r_rows=5000 examined=5000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 5000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=200000 examined=200000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 200000   rows_returned: 1
```

### S7

**keys_strict** (main p1 1.234, opt p1 837.2, branch regression)

mdb-main:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_status_created parts=[status] loops=1 est=20412 r_rows=10 examined=10 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=10 est=1 r_rows=1 examined=10 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20   rows_returned: 10
```
mdb-opt:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=10000 examined=10000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10000 est=1 r_rows=1 examined=10000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20000   rows_returned: 10
```

**keys_nonstrict** (main p1 885.0, opt p1 850.5)

mdb-main:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 10 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=10000 r_rows=10000 examined=10000 covering=no
  filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 10
```
mdb-opt:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10 | 10 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=10000 examined=10000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10000 est=1 r_rows=0 examined=10000 covering=no
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20000   rows_returned: 10
```

**count_statuses** (main p1 49.552, opt p1 70.365, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20412 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=814902 r_rows=390000 examined=390000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 390000   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_retry parts=[status] loops=1 est=770310 r_rows=400000 examined=400000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 400000   rows_returned: 2
```

**count_partitions** (main p1 818.8, opt p1 1.478)

mdb-main:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20412 r_rows=10000 examined=10000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```
mdb-opt:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=20834 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```

**oldest_created** (main p1 818.4, opt p1 799.4)

mdb-main:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=20412 r_rows=10000 examined=10000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```
mdb-opt:
```
id: oldest_created   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=10000 examined=10000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```

**oldest_due_strict** (main p1 852.7, opt p1 828.8)

mdb-main:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=10000 r_rows=10000 examined=10000 covering=no
  older: ref key=idx_outbox_record_record_key_completed_created parts=[record_key,completed_at] loops=10000 est=1 r_rows=1 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=10000 examined=10000 covering=no
  earlier: ref key=idx_outbox_record_key_completed_created_id parts=[record_key,completed_at] loops=10000 est=1 r_rows=1 examined=10000 covering=yes
  filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
  rows_examined_total: 20000   rows_returned: 1
```

**oldest_due_nonstrict** (main p1 2.761, opt p1 790.4, branch regression)

mdb-main:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  o: range key=idx_outbox_record_partition_status_retry parts=[partition_no,status,next_retry_at] loops=1 est=10000 r_rows=10000 examined=10000 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```
mdb-opt:
```
id: oldest_due_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 1 | 1 returned
  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no] loops=1 est=256 r_rows=10000 examined=10000 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 10000   rows_returned: 1
```

### S9

**upd_complete** (main p1 0.358, opt p1 0.643, branch regression)

mdb-main:
```
id: upd_complete   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```
mdb-opt:
```
id: upd_complete   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```

**upd_fail** (main p1 0.144, opt p1 0.502, branch regression)

mdb-main:
```
id: upd_fail   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```
mdb-opt:
```
id: upd_fail   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: range key=PRIMARY parts=[id] loops=1 est=1 r_rows=1 examined=1 covering=no
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1   rows_returned: 1
```

**count_statuses** (main p1 3.101, opt p1 4.046, branch regression)

mdb-main:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=1200 r_rows=1200 examined=1200 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 1200   rows_returned: 1
```
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none | 1 returned
  outbox_record: ref key=idx_outbox_record_status_retry parts=[status] loops=1 est=58406 r_rows=22827 examined=22827 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 22827   rows_returned: 1
```
mdb-opt:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none | 2 returned
  outbox_record: range key=idx_outbox_record_status_completed_partition parts=[status] loops=1 est=49068 r_rows=24027 examined=24027 covering=yes
  filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
  rows_examined_total: 24027   rows_returned: 2
```

## 3. Write cost (`writecost`, committed writes)

### S2-wc

| phase | metric | main p1 | main p2 | opt p1 | opt p2 | change (mean) |
|---|---|---|---|---|---|---|
| insert_1thread | redo bytes/write | 2396.42 | 2458.60 | 2329.80 | 2361.04 | -3% (no difference) |
| insert_1thread | pages/write | 1.35 | 1.38 | 1.30 | 1.26 | -6% (no difference) |
| insert_1thread | latency p50 ms | 0.722 | 0.732 | 0.769 | 0.727 | +3% (no difference) |
| insert_1thread | latency p95 ms | 1.049 | 1.123 | 1.231 | 1.084 | +7% (no difference) |
| insert_4threads | redo bytes/write | 2242.36 | 2258.99 | 2360.67 | 2337.53 | +4% (no difference) |
| insert_4threads | pages/write | 1.28 | 1.30 | 1.27 | 1.26 | -2% (no difference) |
| insert_4threads | latency p50 ms | 1.155 | 1.162 | 1.254 | 1.164 | +4% (no difference) |
| insert_4threads | latency p95 ms | 1.924 | 2.072 | 2.507 | 2.038 | +14% (no difference) |
| upd_complete_committed | redo bytes/write | 5644.12 | 5485.64 | 4744.87 | 4660.88 | -15% |
| upd_complete_committed | pages/write | 4.41 | 2.80 | 3.13 | 1.91 | -30% (no difference) |
| upd_complete_committed | latency p50 ms | 0.716 | 0.722 | 0.743 | 0.691 | -0% (no difference) |
| upd_complete_committed | latency p95 ms | 1.045 | 1.161 | 1.213 | 0.971 | -1% (no difference) |
| upd_retry_committed | redo bytes/write | 2725.75 | 2822.43 | 3184.19 | 3013.03 | +12% (no difference) |
| upd_retry_committed | pages/write | 3.56 | 3.28 | 2.41 | 2.40 | -30% (no difference) |
| upd_retry_committed | latency p50 ms | 0.685 | 0.716 | 0.683 | 0.684 | -2% (no difference) |
| upd_retry_committed | latency p95 ms | 0.901 | 1.067 | 0.843 | 0.892 | -12% (no difference) |
| upd_fail_committed | redo bytes/write | 4831.02 | 4641.46 | 3913.83 | 4202.53 | -14% |
| upd_fail_committed | pages/write | 3.52 | 3.50 | 2.50 | 2.23 | -33% (no difference) |
| upd_fail_committed | latency p50 ms | 0.739 | 0.731 | 0.714 | 0.707 | -3% (no difference) |
| upd_fail_committed | latency p95 ms | 0.963 | 0.894 | 0.956 | 0.940 | +2% (no difference) |
| delete_by_id_committed | redo bytes/write | 3011.98 | 2692.63 | 1929.71 | 1986.78 | -31% |
| delete_by_id_committed | pages/write | 4.62 | 5.10 | 3.62 | 3.62 | -26% (no difference) |
| delete_by_id_committed | latency p50 ms | 0.649 | 0.647 | 0.605 | 0.607 | -6% (no difference) |
| delete_by_id_committed | latency p95 ms | 0.800 | 0.835 | 0.705 | 0.736 | -12% (no difference) |

Index count and sizes (bytes) before / after the writes (pass 1; sizes of pass 2 equal within the page-split noise):

- mdb-main: index_count 6, table 1361870848 -> 1373405184, all secondary indexes 374472704 -> 382926848, tablespace file 1740636160 -> 1786773504
  - PRIMARY: 1361870848 -> 1373405184
  - idx_outbox_record_partition_status_retry: 65798144 -> 70221824
  - idx_outbox_record_record_key_completed_created: 95404032 -> 97255424
  - idx_outbox_record_record_key_created: 90128384 -> 90177536
  - idx_outbox_record_status_created: 61571072 -> 62636032
  - idx_outbox_record_status_retry: 61571072 -> 62636032
- mdb-opt: index_count 5, table 1361870848 -> 1374453760, all secondary indexes 347635712 -> 353681408, tablespace file 1715470336 -> 1757413376
  - PRIMARY: 1361870848 -> 1374453760
  - idx_outbox_record_key_completed_created_id: 95404032 -> 97255424
  - idx_outbox_record_status_completed_created_id: 114278400 -> 115326976
  - idx_outbox_record_status_completed_partition: 66863104 -> 68960256
  - idx_outbox_record_status_completed_retry: 71090176 -> 72138752

### S4-wc

| phase | metric | main p1 | main p2 | opt p1 | opt p2 | change (mean) |
|---|---|---|---|---|---|---|
| insert_1thread | redo bytes/write | 2612.92 | 2576.48 | 5661.88 | 5736.28 | +120% |
| insert_1thread | pages/write | 1.47 | 1.40 | 2.52 | 2.54 | +76% |
| insert_1thread | latency p50 ms | 0.745 | 0.687 | 0.723 | 0.707 | -0% (no difference) |
| insert_1thread | latency p95 ms | 1.236 | 0.924 | 1.179 | 0.949 | -1% (no difference) |
| insert_4threads | redo bytes/write | 2396.83 | 2484.46 | 3087.20 | 3114.33 | +27% |
| insert_4threads | pages/write | 1.33 | 1.33 | 2.15 | 2.24 | +65% |
| insert_4threads | latency p50 ms | 1.247 | 1.174 | 1.459 | 1.336 | +15% |
| insert_4threads | latency p95 ms | 2.479 | 1.896 | 2.649 | 2.476 | +17% (no difference) |
| upd_complete_committed | redo bytes/write | 15435.82 | 14466.76 | 14606.71 | 14579.39 | -2% (no difference) |
| upd_complete_committed | pages/write | 6.08 | 7.64 | 5.82 | 5.94 | -14% (no difference) |
| upd_complete_committed | latency p50 ms | 1.071 | 0.968 | 0.945 | 0.913 | -9% |
| upd_complete_committed | latency p95 ms | 2.851 | 1.741 | 1.690 | 1.429 | -32% |
| upd_retry_committed | redo bytes/write | 3710.46 | 3709.97 | 14317.11 | 14190.42 | +284% |
| upd_retry_committed | pages/write | 1.89 | 1.76 | 3.28 | 3.60 | +88% |
| upd_retry_committed | latency p50 ms | 0.690 | 0.665 | 0.805 | 0.785 | +17% |
| upd_retry_committed | latency p95 ms | 1.094 | 0.860 | 1.295 | 1.037 | +19% (no difference) |
| upd_fail_committed | redo bytes/write | 3912.43 | 3864.11 | 4087.46 | 4140.53 | +6% (no difference) |
| upd_fail_committed | pages/write | 2.73 | 2.79 | 1.64 | 1.54 | -42% (no difference) |
| upd_fail_committed | latency p50 ms | 0.739 | 0.718 | 0.649 | 0.644 | -11% |
| upd_fail_committed | latency p95 ms | 1.394 | 0.954 | 1.035 | 0.846 | -20% (no difference) |
| delete_by_id_committed | redo bytes/write | 1656.12 | 1658.81 | 1821.32 | 1814.38 | +10% (no difference) |
| delete_by_id_committed | pages/write | 2.13 | 1.98 | 0.85 | 0.92 | -57% (no difference) |
| delete_by_id_committed | latency p50 ms | 0.726 | 0.704 | 0.614 | 0.612 | -14% |
| delete_by_id_committed | latency p95 ms | 1.169 | 0.862 | 0.986 | 0.753 | -14% (no difference) |

Index count and sizes (bytes) before / after the writes (pass 1; sizes of pass 2 equal within the page-split noise):

- mdb-main: index_count 6, table 2655813632 -> 2669445120, all secondary indexes 704315392 -> 727859200, tablespace file 3372220416 -> 3447717888
  - PRIMARY: 2655813632 -> 2669445120
  - idx_outbox_record_partition_status_retry: 121569280 -> 131072000
  - idx_outbox_record_record_key_completed_created: 181387264 -> 191201280
  - idx_outbox_record_record_key_created: 175095808 -> 176144384
  - idx_outbox_record_status_created: 113131520 -> 115245056
  - idx_outbox_record_status_retry: 113131520 -> 114196480
- mdb-opt: index_count 5, table 2655813632 -> 2668396544, all secondary indexes 639516672 -> 721027072, tablespace file 3305111552 -> 3443523584
  - PRIMARY: 2655813632 -> 2668396544
  - idx_outbox_record_key_completed_created_id: 181387264 -> 196788224
  - idx_outbox_record_status_completed_created_id: 211795968 -> 225427456
  - idx_outbox_record_status_completed_partition: 119472128 -> 168787968
  - idx_outbox_record_status_completed_retry: 126861312 -> 130023424

## 4. S9 churn (pass 1 only; pass 2 skipped by user decision; repeated in phase 6)

**S9: pass 1 only (pass 2 skipped by user decision; repeated in phase 6).** mdb-main's churn hit the 2700 s retention cap (`--retention-max-s 2700`, added to the harness): it removed 28,000 rows by retention vs 500,000 on the branch, so mdb-main's post-churn table holds about 1.3M COMPLETED rows versus about 0.83M for opt. Post-churn P1/P2 timings of mdb-main are therefore measured on a larger table; comparison of the post-churn timings is not like for like.

### mdb-main churn.txt
```
innodb_history_list_length_at_start: 9 (threshold < 1000, waited 0.0 s)
== before ==
# rows_by_status
COMPLETED	986000
FAILED	12800
NEW	1200
# table_rows,data_length,index_length
922969	1361870848	374472704
# index_sizes
PRIMARY	1361870848
idx_outbox_record_partition_status_retry	65798144
idx_outbox_record_record_key_completed_created	95404032
idx_outbox_record_record_key_created	90128384
idx_outbox_record_status_created	61571072
idx_outbox_record_status_retry	61571072
# index_count (incl. primary)
6
# tablespace file_size,allocated_size
1740636160	1741238272
# history_list_length
Innodb_history_list_length	9
history_list_length_before: 9
removed_by_retention: 28000
retention_capped_at_s: 2700
churn_seconds: 2950
== after churn (no manual VACUUM/ANALYZE/OPTIMIZE) ==
# rows_by_status
COMPLETED	1301213
FAILED	22827
NEW	1200
# table_rows,data_length,index_length
1124379	1742356480	568082432
# index_sizes
PRIMARY	1742356480
idx_outbox_record_partition_status_retry	100646912
idx_outbox_record_record_key_completed_created	144687104
idx_outbox_record_record_key_created	137347072
idx_outbox_record_status_created	93224960
idx_outbox_record_status_retry	92176384
# index_count (incl. primary)
6
# tablespace file_size,allocated_size
2361393152	2362015744
# history_list_length
Innodb_history_list_length	1
history_list_length_after: 1
innodb_global_deltas: os_log_written=2511263568 pages_written=1270857 data_written=40665153536 buffer_pool_read_requests=188069085 buffer_pool_reads=30008575 row_lock_waits=0 row_lock_time=0 deadlocks=0
```
### mdb-opt churn.txt
```
innodb_history_list_length_at_start: 8 (threshold < 1000, waited 0.0 s)
== before ==
# rows_by_status
COMPLETED	986000
FAILED	12800
NEW	1200
# table_rows,data_length,index_length
832320	1361870848	347635712
# index_sizes
PRIMARY	1361870848
idx_outbox_record_key_completed_created_id	95404032
idx_outbox_record_status_completed_created_id	114278400
idx_outbox_record_status_completed_partition	66863104
idx_outbox_record_status_completed_retry	71090176
# index_count (incl. primary)
5
# tablespace file_size,allocated_size
1715470336	1716027392
# history_list_length
Innodb_history_list_length	8
history_list_length_before: 8
removed_by_retention: 500000
retention_capped_at_s: no
churn_seconds: 311
== after churn (no manual VACUUM/ANALYZE/OPTIMIZE) ==
# rows_by_status
COMPLETED	829213
FAILED	22827
NEW	1200
# table_rows,data_length,index_length
779636	1749417984	413974528
# index_sizes
PRIMARY	1749417984
idx_outbox_record_key_completed_created_id	146767872
idx_outbox_record_status_completed_created_id	124960768
idx_outbox_record_status_completed_partition	68485120
idx_outbox_record_status_completed_retry	73760768
# index_count (incl. primary)
5
# tablespace file_size,allocated_size
2311061504	2311639040
# history_list_length
Innodb_history_list_length	6
history_list_length_after: 6
innodb_global_deltas: os_log_written=3227032607 pages_written=2589091 data_written=83874217984 buffer_pool_read_requests=56989029 buffer_pool_reads=2774651 row_lock_waits=0 row_lock_time=0 deadlocks=0
```
### S9 post-churn timings (pass 1)

| query | main | opt | change |
|---|---|---|---|
| keys_strict | 1.231 | 0.719 | -42% |
| keys_nonstrict | 3.210 | 0.684 | -79% |
| pages | 0.362 | 0.358 | -1% (no difference) |
| insert | 0.341 | 0.408 | +20% (no difference) |
| upd_complete | 0.358 | 0.643 | +80% |
| upd_retry | 0.128 | 0.275 | +115% (no difference) |
| upd_fail | 0.144 | 0.502 | +249% |
| delete_by_id | 1.054 | 0.757 | -28% (no difference) |
| count_statuses | 3.101 | 4.046 | +30% |
| count_partitions | 1.459 | 0.420 | -71% |
| oldest_created | 1.492 | 0.586 | -61% |
| oldest_due_strict | 3.802 | 0.479 | -87% |
| oldest_due_nonstrict | 1.055 | 0.714 | -32% |

## 5. S10 concurrency

| label | metric | main p1 | main p2 | opt p1 | opt p2 |
|---|---|---|---|---|---|
| S10-S2-p1 | processed/s | 513.6 | 513.5 | 513.0 | 513.9 |
| S10-S2-p1 | inserted/s | 498.0 | 498.1 | 497.8 | 498.2 |
| S10-S2-p1 | keys p50/p95 | 1.29/1.72 | 1.28/1.51 | 0.61/0.90 | 0.57/0.74 |
| S10-S2-p1 | pages p50/p95 | 0.12/0.21 | 0.12/0.20 | 0.20/0.27 | 0.18/0.24 |
| S10-S2-p1 | update p50/p95 | 0.61/1.13 | 0.60/1.11 | 0.62/1.16 | 0.61/1.12 |
| S10-S2-p1 | insert p50/p95 | 0.87/1.48 | 0.74/1.40 | 0.78/1.54 | 0.71/1.42 |
| S10-S2-p1 | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S2-p1 | history list start/end | 9/848 | 0/86 | 8/525 | 9/689 |
| S10-S2-p1 | errors | 0 | 0 | 0 | 0 |
| S10-S2-p1-unthr | processed/s | 342.6 | 394.0 | 1.9 | 2.3 |
| S10-S2-p1-unthr | inserted/s | 2251.6 | 2578.5 | 2132.3 | 2180.9 |
| S10-S2-p1-unthr | keys p50/p95 | 7.58/28.49 | 7.42/20.11 | 1.42/40151.72 | 0.86/41089.91 |
| S10-S2-p1-unthr | pages p50/p95 | 0.24/4.29 | 0.21/3.41 | 3.50/27.75 | 3.27/14.14 |
| S10-S2-p1-unthr | update p50/p95 | 1.20/2.24 | 1.18/2.12 | 1.23/3.28 | 1.10/2.48 |
| S10-S2-p1-unthr | insert p50/p95 | 1.23/2.56 | 1.20/2.38 | 1.22/2.55 | 1.21/2.54 |
| S10-S2-p1-unthr | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S2-p1-unthr | history list start/end | 0/2235 | 0/1431 | 8/158779 | 8/141795 |
| S10-S2-p1-unthr | errors | 0 | 0 | 0 | 0 |
| S10-S2-p4 | processed/s | 511.9 | 513.1 | 512.8 | 513.0 |
| S10-S2-p4 | inserted/s | 496.7 | 497.6 | 497.0 | 497.7 |
| S10-S2-p4 | keys p50/p95 | 0.59/1.38 | 0.56/1.60 | 0.44/0.69 | 0.41/0.62 |
| S10-S2-p4 | pages p50/p95 | 0.19/0.28 | 0.18/0.27 | 0.18/0.28 | 0.17/0.25 |
| S10-S2-p4 | update p50/p95 | 0.97/1.50 | 1.08/1.58 | 0.95/1.37 | 0.98/1.32 |
| S10-S2-p4 | insert p50/p95 | 0.78/1.62 | 0.77/1.59 | 0.76/1.56 | 0.71/1.46 |
| S10-S2-p4 | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S2-p4 | history list start/end | 9/399 | 9/831 | 0/176 | 8/391 |
| S10-S2-p4 | errors | 0 | 0 | 0 | 0 |
| S10-S2-p4-unthr | processed/s | 968.9 | 981.3 | 1325.4 | 1400.2 |
| S10-S2-p4-unthr | inserted/s | 2067.3 | 2197.5 | 2095.7 | 2311.6 |
| S10-S2-p4-unthr | keys p50/p95 | 9.34/36.33 | 9.42/37.38 | 0.92/21.25 | 1.05/20.98 |
| S10-S2-p4-unthr | pages p50/p95 | 0.26/4.48 | 0.25/4.52 | 2.71/6.94 | 2.48/6.98 |
| S10-S2-p4-unthr | update p50/p95 | 1.27/7.95 | 1.24/7.81 | 1.25/5.20 | 1.23/5.03 |
| S10-S2-p4-unthr | insert p50/p95 | 1.28/2.92 | 1.23/2.76 | 1.29/2.93 | 1.24/2.72 |
| S10-S2-p4-unthr | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S2-p4-unthr | history list start/end | 9/1781 | 9/1925 | 8/3167 | 8/971 |
| S10-S2-p4-unthr | errors | 0 | 0 | 0 | 0 |
| S10-S4-p4 | processed/s | 700.7 | 768.1 | 776.4 | 921.6 |
| S10-S4-p4 | inserted/s | 497.8 | 497.8 | 418.9 | 459.3 |
| S10-S4-p4 | keys p50/p95 | 11.82/48.77 | 10.67/44.64 | 10.73/43.38 | 9.91/36.73 |
| S10-S4-p4 | pages p50/p95 | 4.98/24.97 | 4.65/22.72 | 0.92/7.33 | 0.80/7.13 |
| S10-S4-p4 | update p50/p95 | 1.80/7.81 | 1.62/7.07 | 1.80/6.33 | 1.62/6.08 |
| S10-S4-p4 | insert p50/p95 | 1.28/3.65 | 1.24/3.61 | 1.40/3.83 | 1.34/3.89 |
| S10-S4-p4 | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S4-p4 | history list start/end | 9/18353 | 9/17573 | 0/30735 | 0/35984 |
| S10-S4-p4 | errors | 0 | 0 | 0 | 0 |
| S10-S4-p4-unthr | processed/s | 370.1 | 387.9 | 544.5 | 538.6 |
| S10-S4-p4-unthr | inserted/s | 1412.5 | 1484.3 | 1309.8 | 1334.1 |
| S10-S4-p4-unthr | keys p50/p95 | 21.64/105.90 | 20.90/97.13 | 17.63/65.83 | 16.71/66.70 |
| S10-S4-p4-unthr | pages p50/p95 | 7.08/30.88 | 6.75/33.19 | 1.32/9.55 | 1.27/11.00 |
| S10-S4-p4-unthr | update p50/p95 | 2.91/23.82 | 2.79/22.97 | 2.45/12.20 | 2.39/13.09 |
| S10-S4-p4-unthr | insert p50/p95 | 1.88/5.70 | 1.84/5.62 | 2.17/6.00 | 1.96/5.91 |
| S10-S4-p4-unthr | lock waits (lock waits: waits/deadlocks) | 0/0 | 0/0 | 0/0 | 0/0 |
| S10-S4-p4-unthr | history list start/end | 9/12130 | 9/13859 | 0/44475 | 8/46583 |
| S10-S4-p4-unthr | errors | 0 | 0 | 0 | 0 |

## 6. Equivalence (HARNESS_SAMPLE_ORDERED=1, mdb-main vs mdb-opt)

- **S1**: PASS 28 (/home/azamojski/namastack-outbox/docs/db-mariadb/results/cmp/pass1/equiv/mariadb/S1/mdb-main-vs-mdb-opt.md)
- **S2**: PASS 26, SKIP 2 (/home/azamojski/namastack-outbox/docs/db-mariadb/results/cmp/pass1/equiv/mariadb/S2/mdb-main-vs-mdb-opt.md)
  - find_pending_all: SKIP - unbounded
  - find_failed_all: SKIP - unbounded
- **S5**: PASS 25, SKIP 2, WARN 1 (/home/azamojski/namastack-outbox/docs/db-mariadb/results/cmp/pass1/equiv/mariadb/S5/mdb-main-vs-mdb-opt.md)
  - keys_nonstrict: WARN - limited lists differ (first difference at position 2: a=order-000000000000000000000000000016 b=order-000000000000000000000000000069, 1 key(s) only in a, 1 only in b) but full key sets are equal (100);
  - find_pending_all: SKIP - unbounded
  - find_failed_all: SKIP - unbounded
- **S7**: PASS 26, SKIP 2 (/home/azamojski/namastack-outbox/docs/db-mariadb/results/cmp/pass1/equiv/mariadb/S7/mdb-main-vs-mdb-opt.md)
  - find_pending_all: SKIP - unbounded
  - find_failed_all: SKIP - unbounded

## 7. Cells over 2x spread threshold (pass 1 vs pass 2)

- S8-S7 keys_nonstrict mdb-opt: spread 26%
- S16-S2 keys_strict mdb-main: spread 69%
- S16-S2 keys_strict mdb-opt: spread 64%
- S16-S2 keys_nonstrict mdb-main: spread 66%
- S16-S2 keys_nonstrict mdb-opt: spread 66%
- S16-S2 pages mdb-main: spread 67%
- S16-S2 pages mdb-opt: spread 72%
- S16-S2 count_statuses mdb-main: spread 102%
- S16-S2 count_statuses mdb-opt: spread 92%
- S16-S2 count_partitions mdb-main: spread 0.082 ms
- S16-S2 count_partitions mdb-opt: spread 0.221 ms
- S16-S2 oldest_created mdb-main: spread 0.127 ms
- S16-S2 oldest_created mdb-opt: spread 0.126 ms
- S16-S2 oldest_due_strict mdb-main: spread 66%
- S16-S2 oldest_due_strict mdb-opt: spread 65%
- S16-S2 oldest_due_nonstrict mdb-main: spread 0.104 ms
- S16-S2 oldest_due_nonstrict mdb-opt: spread 0.160 ms
- S16-S2 count_new mdb-main: spread 0.138 ms
- S16-S2 count_new mdb-opt: spread 0.203 ms
- S16-S2 count_failed mdb-main: spread 107%
- S16-S2 count_failed mdb-opt: spread 102%
- S16-S2 count_completed mdb-main: spread 104%
- S16-S2 count_completed mdb-opt: spread 101%
- S16-S2 count_partition_single mdb-opt: spread 0.179 ms
- S16-S4 keys_nonstrict mdb-main: spread 29%
- S1 keys_strict mdb-main: spread 70%
- S1 keys_strict mdb-opt: spread 73%
- S1 keys_nonstrict mdb-main: spread 73%
- S1 keys_nonstrict mdb-opt: spread 66%
- S1 pages mdb-main: spread 77%
- S1 pages mdb-opt: spread 87%
- S1 count_statuses mdb-main: spread 0.120 ms
- S1 count_partitions mdb-main: spread 75%
- S1 count_partitions mdb-opt: spread 0.212 ms
- S1 oldest_created mdb-main: spread 72%
- S1 oldest_created mdb-opt: spread 71%
- S1 oldest_due_strict mdb-main: spread 72%
- S1 oldest_due_strict mdb-opt: spread 65%
- S1 oldest_due_nonstrict mdb-main: spread 74%
- S1 oldest_due_nonstrict mdb-opt: spread 67%
- S1 count_completed mdb-main: spread 100%
- S1 count_completed mdb-opt: spread 96%
- S2 keys_strict mdb-main: spread 74%
- S2 keys_strict mdb-opt: spread 58%
- S2 keys_nonstrict mdb-main: spread 95%
- S2 keys_nonstrict mdb-opt: spread 72%
- S2 pages mdb-main: spread 69%
- S2 pages mdb-opt: spread 58%
- S2 count_statuses mdb-main: spread 106%
- S2 count_statuses mdb-opt: spread 106%
- S2 count_partitions mdb-main: spread 74%
- S2 count_partitions mdb-opt: spread 80%
- S2 oldest_created mdb-main: spread 77%
- S2 oldest_created mdb-opt: spread 80%
- S2 oldest_due_strict mdb-main: spread 79%
- S2 oldest_due_strict mdb-opt: spread 73%
- S2 oldest_due_nonstrict mdb-main: spread 84%
- S2 oldest_due_nonstrict mdb-opt: spread 81%
- S2 count_new mdb-main: spread 0.176 ms
- S2 count_new mdb-opt: spread 0.169 ms
- S2 count_failed mdb-main: spread 107%
- S2 count_failed mdb-opt: spread 100%
- S2 count_completed mdb-main: spread 103%
- S2 count_completed mdb-opt: spread 97%
- S2 count_partition_single mdb-opt: spread 0.152 ms
- S4 keys_strict mdb-main: spread 56%
- S4 keys_nonstrict mdb-opt: spread 25%
- S4 pages mdb-main: spread 76%
- S4 count_statuses mdb-opt: spread 101%
- S4 count_partitions mdb-main: spread 34%
- S4 count_partitions mdb-opt: spread 108%
- S4 oldest_due_strict mdb-main: spread 42%
- S4 count_completed mdb-main: spread 95%
- S4 count_partition_single mdb-main: spread 91%
- S4 count_partition_single mdb-opt: spread 100%
- S4-b100 keys_nonstrict mdb-main: spread 28%
- S4-b100 count_partitions mdb-opt: spread 22%
- S4-b100 oldest_due_nonstrict mdb-main: spread 22423%

After the rerun (rerun vs pass 2), still above 2x threshold:

- S4-b100 oldest_due_nonstrict mdb-main: spread 18191%
