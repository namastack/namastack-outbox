# Final measurement (phase 6): `mdb-main`, `mdb-opt`, `mdb-final`

MariaDB 12.3.3 (`mariadb:12`, image id 2215e9af9663), lane 1 only for every timed cell (lane 2 idle; it ran only the equivalence checks, before pass 1). Raw data `results/final/{pass1,pass2,rerun1,bp2g,equiv}/`, logs `results/final/logs/driver.log` (one line per cell with load and free disk) and `results/final/logs/final-equiv-L2.log`; harness logs of lane 1 are in `pass*/logs/`. Drivers `experiments/final-run.sh` (all stages), `experiments/final-rerun.sh`, summarizer `experiments/final-summarize.py`. Driver record `results/final/driver.txt`.

## 0. Run record

- **Worktrees** (`results/final/worktrees.txt`; `git status --short -- . ':!docs'` empty for all three before the run; the worktrees were not modified):

| Variant | Worktree | `git rev-parse HEAD` |
|---|---|---|
| `mdb-main` | `/home/azamojski/outbox-wt-mdb-main` | `f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f` |
| `mdb-opt` | `/home/azamojski/outbox-wt-mdb-opt` | `2a2663de2f2028790180399293981f7eca587ad1` |
| `mdb-final` | `/home/azamojski/outbox-wt-mdb-final` (`exp/mdb-final`) | `f31bc505c4357273826cf37ab005e1b33880f878` |

- **Catalog / DDL.** Each variant used its own worktree DDL and its own extracted catalog (`--catalog-variant`), no query overlays. `mdb-final` catalog re-extracted (`bin/extract-catalog.sh mdb-final ...`) before measuring. Checks: `catalog/mdb-final/mariadb/oldest_created.sql` header `maxrows: one`; `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_nonstrict` equal `experiments/r2/forms-a/queries/*.sql` (SQL text, whitespace aside); the other 24 ids equal `catalog/mdb-opt/mariadb/` apart from the `-- source` line. No difference, so measuring went ahead. `mdb-final` indexes (from `env.txt`): `key_completed_created_id`, `status_completed_created_id`, `status_completed_retry`, `status_partition_created`, PRIMARY (5 incl. primary); `mdb-opt`: same without `status_partition_created`, with `status_completed_partition`; `mdb-main`: 6 (PRIMARY + 5).
- **Ids: UUIDv4 only.** `HARNESS_ID_VERSION` never set (driver `unset`s it); the driver checked the lane-1 snapshot marker for `ids=uuidv7` before every cell (none found, none would continue); `ids=v4` written to `results/final/{pass1,pass2,rerun1,bp2g}/<variant>/ids.txt` and `results/final/equiv-ids.txt`. No `ids=uuidv7` line in `harness/build/snap-mariadb-L{1,2}.txt` at any check.
- **Timings of the run (2026-11-07).** Equivalence (lane 2) 06:59-07:46; pass 1 07:46-13:33 (order main, opt, final; S9 included; S9 `mdb-main` hit the 2700 s retention cap); container stop/start at 13:33; pass 2 13:33-18:14 (order final, opt, main; scenarios reversed; no S9); 2G buffer-pool run 18:14-18:32; rerun 18:33-18:58:29. Bench cells `--groups HOT,WRITE,MON`, 5 warm-up + 20 reps (1 + 5, or 1 execution above 30 s, by the harness rule), `--prepare` per run; the three variants of a cell back to back on the same snapshot (gen once per scenario and pass).
- **Per-query limits.** `mdb-main` 120 s (no query reached it; the longest "very slow" executions are about 76-82 s, `oldest_due_nonstrict` S4/S4-b100). `mdb-opt` 10 s on S4, S4-b100, S5, S6, S7 (queries stopped: S4 5, S4-b100 5, S5 6, S6 6 per pass; none on S7), 60 s elsewhere (none stopped). `mdb-final`: `--limit-from <pass-1 mdb-opt timings> --limit-factor 2 --limit-min-s 60`; no `mdb-final` query was stopped in any cell. `pages` keys: `experiments/r1/s4-pages-keys.txt` / `s4-pages-keys-b100.txt` on S4 / S4-b100 for all three variants, elsewhere each variant's own `keys_strict` keys. A stopped query shows `over N s`; `mdb-opt` S5 `pages` therefore is not measured (its `keys_strict` stopped).
- **Host load.** `/proc/loadavg` and free disk logged at every cell start in `logs/driver.log`: 1-minute load 0.5-4.11 (max 4.11, `mdb-opt` S8-S2 pass 2), free disk 40 GB. The rerun rule "load > 18" never triggered.
- **Noise thresholds** (STATE.md): HOT 11 %, MON 10 % (0.04 ms absolute below 0.3 ms), WRITE rolled-back statements 60 % and >= 0.2 ms, writecost redo 12 %, latency p50 8 % / p95 25 %, pages/write 64 % (supporting only). `(nd)` = no difference (below threshold). Changes use the mean of pass 1 and pass 2. `mdb-main` has no `count_statuses` statement; its value is `count_new` + `count_failed`. `*` = very slow query (1 execution). Writes (insert, upd_*, delete_by_id) are rolled-back single statements (60 % threshold); committed write cost is in section 3.
- **Reruns.** One rule hit: `SPREAD>2x` on `mdb-main` `oldest_due_nonstrict`, S4 and S4-b100 (pass 1 75.3 s / 81.5 s, 1 execution; pass 2 411 ms, 20 reps). Rerun once (`results/final/rerun1/mdb-main/`): 76.3 s (S4) and 75.2 s (S4-b100), both 1 execution. The tables below use the pass 1 / pass 2 values as measured (their mean is not meaningful for these two cells); the rerun agrees with pass 1. No other cell had a spread > 2x threshold, none a load > 18.

## 1. Reads: query x label, partial ownership first

Medians in ms, pass 1 / pass 2 per variant. `final vs main` / `final vs opt` = change of the mean of both passes; `X over limit (final N ms)` = the reference was stopped at its limit, `final` answered in N ms.

### S8-S2

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.588 | 0.602 | 0.478 | 0.493 | 0.469 | 0.466 | -21% | -4% (nd) |  |
| keys_nonstrict | 0.997 | 0.981 | 0.493 | 0.509 | 0.507 | 0.497 | -49% | +0% (nd) |  |
| pages | 0.379 | 0.395 | 0.302 | 0.325 | 0.339 | 0.329 | -14% | +7% (nd) |  |
| insert | 0.593 | 0.607 | 0.424 | 0.437 | 0.424 | 0.425 | -29% (nd) | -1% (nd) |  |
| upd_complete | 0.444 | 0.463 | 0.339 | 0.343 | 0.342 | 0.372 | -21% (nd) | +5% (nd) |  |
| upd_retry | 0.283 | 0.285 | 0.244 | 0.255 | 0.273 | 0.258 | -7% (nd) | +6% (nd) |  |
| upd_fail | 0.293 | 0.287 | 0.196 | 0.202 | 0.284 | 0.212 | -14% (nd) | +25% (nd) |  |
| delete_by_id | 0.414 | 0.387 | 0.321 | 0.333 | 0.369 | 0.327 | -13% (nd) | +6% (nd) |  |
| count_statuses | 1.842 | 1.906 | 2.351 | 2.401 | 2.499 | 2.511 | +34% | +5% (nd) |  |
| count_partitions | 0.292 | 0.291 | 0.268 | 0.272 | 0.286 | 0.286 | -2% (nd) | +6% (nd) |  |
| oldest_created | 0.572 | 0.565 | 0.408 | 0.395 | 0.219 | 0.218 | -62% | -46% |  |
| oldest_due_strict | 1.269 | 1.252 | 0.318 | 0.305 | 0.323 | 0.324 | -74% | +4% (nd) |  |
| oldest_due_nonstrict | 0.344 | 0.352 | 0.469 | 0.461 | 0.317 | 0.319 | -9% (nd) | -32% |  |
| count_new | 0.197 | 0.191 | 0.199 | 0.199 | 0.206 | 0.216 | +9% (nd) | +6% (nd) |  |
| count_failed | 1.645 | 1.715 | 1.736 | 1.758 | 1.832 | 1.887 | +11% | +6% (nd) |  |
| count_completed | 90.6 | 94.4 | 98.8 | 100.0 | 98.6 | 98.5 | +7% (nd) | -1% (nd) |  |
| count_partition_single | 0.098 | 0.099 | 0.211 | 0.191 | 0.101 | 0.094 | -1% (nd) | -51% |  |

### S8-S4

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.715 | 0.703 | 0.526 | 0.543 | 0.496 | 0.501 | -30% | -7% (nd) |  |
| keys_nonstrict | 20628.9* | 23212.7* | 0.554 | 0.555 | 0.532 | 0.544 | -100% | -3% (nd) |  |
| pages | 0.323 | 0.321 | 0.323 | 0.328 | 0.323 | 0.315 | -1% (nd) | -2% (nd) |  |
| insert | 0.649 | 0.624 | 0.484 | 0.477 | 0.497 | 0.521 | -20% (nd) | +6% (nd) |  |
| upd_complete | 0.765 | 0.805 | 0.814 | 0.768 | 0.872 | 0.942 | +16% (nd) | +15% (nd) |  |
| upd_retry | 0.401 | 0.389 | 0.357 | 0.425 | 0.409 | 0.441 | +8% (nd) | +9% (nd) |  |
| upd_fail | 0.286 | 0.286 | 0.278 | 0.349 | 0.176 | 0.329 | -12% (nd) | -19% (nd) |  |
| delete_by_id | 0.477 | 0.494 | 0.538 | 0.527 | 0.434 | 0.506 | -3% (nd) | -12% (nd) |  |
| count_statuses | 85.5 | 84.9 | 106.9 | 116.0 | 106.9 | 106.9 | +26% | -4% (nd) |  |
| count_partitions | 30.0 | 29.9 | 99.7 | 114.0 | 29.3 | 29.2 | -2% (nd) | -73% |  |
| oldest_created | 14073.6* | 15028.0* | 162.4 | 160.2 | 0.257 | 0.257 | -100% | -100% |  |
| oldest_due_strict | 18918.4* | 19116.8* | 0.390 | 0.375 | 0.379 | 0.373 | -100% | -2% (nd) |  |
| oldest_due_nonstrict | 45.7 | 45.5 | 142.6 | 140.7 | 0.398 | 0.406 | -99% | -100% |  |
| count_new | 85.4 | 84.8 | 92.7 | 102.7 | 93.3 | 92.8 | +9% (nd) | -5% (nd) |  |
| count_failed | 0.072 | 0.076 | 0.080 | 0.093 | 0.084 | 0.095 | +21% (nd) | +3% (nd) |  |
| count_completed | 92.1 | 92.0 | 100.6 | 100.5 | 100.5 | 100.3 | +9% (nd) | -0% (nd) |  |
| count_partition_single | 0.479 | 0.480 | 85.8 | 95.6 | 0.498 | 0.486 | +3% (nd) | -99% |  |

### S8-S5

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 13207.7* | 13300.2* | 106.5 | 107.3 | 53.6 | 54.6 | -100% | -49% |  |
| keys_nonstrict | 2145.4* | 2211.8* | 4.876 | 4.831 | 0.601 | 0.592 | -100% | -88% |  |
| pages | 4019.8* | 4022.6* | 1270.2 | 1262.8 | 1246.8 | 1292.3 | -68% | +0% (nd) |  |
| insert | 0.472 | 0.542 | 0.422 | 0.449 | 0.448 | 0.401 | -16% (nd) | -3% (nd) |  |
| upd_complete | 0.594 | 0.570 | 0.378 | 0.378 | 0.408 | 0.321 | -37% (nd) | -4% (nd) |  |
| upd_retry | 0.267 | 0.300 | 0.234 | 0.240 | 0.240 | 0.150 | -31% (nd) | -18% (nd) |  |
| upd_fail | 0.235 | 0.264 | 0.240 | 0.247 | 0.245 | 0.158 | -19% (nd) | -17% (nd) |  |
| delete_by_id | 0.428 | 0.471 | 0.287 | 0.323 | 0.244 | 0.244 | -46% (nd) | -20% (nd) |  |
| count_statuses | 17.2 | 17.6 | 21.6 | 21.4 | 21.7 | 21.5 | +24% | +0% (nd) |  |
| count_partitions | 4.299 | 4.282 | 19.6 | 19.6 | 4.271 | 4.266 | -1% (nd) | -78% |  |
| oldest_created | 2324.0* | 2317.6* | 31.5 | 31.7 | 0.217 | 0.205 | -100% | -99% |  |
| oldest_due_strict | 2423.3* | 2414.2* | 0.581 | 0.558 | 0.576 | 0.560 | -100% | -0% (nd) |  |
| oldest_due_nonstrict | 6.399 | 6.399 | 29.2 | 29.1 | 0.321 | 0.305 | -95% | -99% |  |
| count_new | 17.1 | 17.6 | 18.6 | 18.9 | 18.9 | 19.1 | +10% (nd) | +1% (nd) |  |
| count_failed | 0.086 | 0.092 | 0.078 | 0.096 | 0.077 | 0.093 | -4% (nd) | -2% (nd) |  |
| count_completed | 27.8 | 28.3 | 30.4 | 30.5 | 30.6 | 30.8 | +9% (nd) | +1% (nd) |  |
| count_partition_single | 0.298 | 0.313 | 17.1 | 17.1 | 0.292 | 0.283 | -6% (nd) | -98% |  |

### S8-S6

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 4.890 | 4.799 | 0.733 | 0.758 | 0.715 | 0.720 | -85% | -4% (nd) |  |
| keys_nonstrict | 3.123 | 3.114 | 0.764 | 0.762 | 0.755 | 0.759 | -76% | -1% (nd) |  |
| pages | 0.351 | 0.363 | 0.314 | 0.326 | 0.310 | 0.325 | -11% | -1% (nd) |  |
| insert | 0.590 | 0.577 | 0.409 | 0.427 | 0.420 | 0.363 | -33% (nd) | -6% (nd) |  |
| upd_complete | 0.428 | 0.427 | 0.610 | 0.644 | 0.624 | 0.562 | +39% (nd) | -5% (nd) |  |
| upd_retry | 0.260 | 0.247 | 0.380 | 0.393 | 0.376 | 0.293 | +32% (nd) | -13% (nd) |  |
| upd_fail | 0.235 | 0.174 | 0.265 | 0.184 | 0.311 | 0.213 | +28% (nd) | +17% (nd) |  |
| delete_by_id | 0.408 | 0.348 | 0.409 | 0.431 | 0.422 | 0.419 | +11% (nd) | +0% (nd) |  |
| count_statuses | 17.4 | 17.5 | 21.5 | 21.5 | 23.5 | 23.3 | +34% | +9% (nd) |  |
| count_partitions | 6.328 | 6.230 | 19.9 | 19.9 | 6.177 | 6.188 | -2% (nd) | -69% |  |
| oldest_created | 3964.2* | 3920.1* | 33.2 | 33.3 | 0.214 | 0.221 | -100% | -99% |  |
| oldest_due_strict | 4.192 | 4.211 | 0.412 | 0.417 | 0.410 | 0.395 | -90% | -3% (nd) |  |
| oldest_due_nonstrict | 0.540 | 0.542 | 1.131 | 1.134 | 0.344 | 0.332 | -38% | -70% |  |
| count_new | 17.3 | 17.4 | 18.7 | 18.9 | 20.8 | 21.0 | +20% | +11% |  |
| count_failed | 0.078 | 0.094 | 0.102 | 0.093 | 0.088 | 0.095 | +6% (nd) | -6% (nd) |  |
| count_completed | 74.8 | 76.2 | 80.9 | 82.4 | 80.0 | 81.4 | +7% (nd) | -1% (nd) |  |
| count_partition_single | 0.169 | 0.164 | 17.2 | 17.3 | 0.163 | 0.167 | -1% (nd) | -99% |  |

### S8-S7

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.577 | 0.590 | 0.484 | 0.481 | 0.443 | 0.439 | -24% | -9% (nd) |  |
| keys_nonstrict | 5.900 | 5.801 | 0.506 | 0.495 | 0.480 | 0.495 | -92% | -3% (nd) |  |
| pages | 0.357 | 0.349 | 0.320 | 0.327 | 0.319 | 0.317 | -10% (nd) | -2% (nd) |  |
| insert | 0.582 | 0.580 | 0.404 | 0.427 | 0.395 | 0.404 | -31% (nd) | -4% (nd) |  |
| upd_complete | 0.773 | 0.749 | 0.476 | 0.465 | 0.483 | 0.463 | -38% (nd) | +1% (nd) |  |
| upd_retry | 0.338 | 0.370 | 0.236 | 0.250 | 0.238 | 0.249 | -31% (nd) | +0% (nd) |  |
| upd_fail | 0.285 | 0.297 | 0.297 | 0.292 | 0.216 | 0.287 | -14% (nd) | -15% (nd) |  |
| delete_by_id | 0.360 | 0.413 | 0.340 | 0.332 | 0.328 | 0.328 | -15% (nd) | -2% (nd) |  |
| count_statuses | 48.4 | 48.7 | 68.7 | 69.0 | 69.0 | 68.7 | +42% | +0% (nd) |  |
| count_partitions | 0.564 | 0.547 | 1.143 | 1.176 | 0.553 | 0.553 | -0% (nd) | -52% |  |
| oldest_created | 3.058 | 3.036 | 1.862 | 1.872 | 0.210 | 0.216 | -93% | -89% |  |
| oldest_due_strict | 7.848 | 7.798 | 0.330 | 0.325 | 0.313 | 0.309 | -96% | -5% (nd) |  |
| oldest_due_nonstrict | 0.767 | 0.778 | 1.915 | 1.909 | 0.312 | 0.315 | -59% | -84% |  |
| count_new | 0.952 | 0.979 | 1.039 | 1.045 | 1.022 | 1.052 | +7% (nd) | -0% (nd) |  |
| count_failed | 47.4 | 47.7 | 53.5 | 54.1 | 53.8 | 54.6 | +14% | +1% (nd) |  |
| count_completed | 55.7 | 56.5 | 60.3 | 61.9 | 60.2 | 61.4 | +8% (nd) | -1% (nd) |  |
| count_partition_single | 0.090 | 0.089 | 0.961 | 0.989 | 0.103 | 0.086 | +6% (nd) | -90% |  |

### S16-S2

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.639 | 0.631 | 0.639 | 0.628 | 0.618 | 0.614 | -3% (nd) | -3% (nd) |  |
| keys_nonstrict | 0.405 | 0.407 | 0.646 | 0.636 | 0.673 | 0.659 | +64% | +4% (nd) |  |
| pages | 0.393 | 0.377 | 0.316 | 0.322 | 0.327 | 0.328 | -15% | +3% (nd) |  |
| insert | 0.645 | 0.664 | 0.445 | 0.465 | 0.455 | 0.417 | -33% (nd) | -4% (nd) |  |
| upd_complete | 0.443 | 0.434 | 0.329 | 0.326 | 0.323 | 0.249 | -35% (nd) | -13% (nd) |  |
| upd_retry | 0.272 | 0.284 | 0.268 | 0.259 | 0.245 | 0.170 | -25% (nd) | -21% (nd) |  |
| upd_fail | 0.225 | 0.309 | 0.270 | 0.206 | 0.262 | 0.195 | -14% (nd) | -4% (nd) |  |
| delete_by_id | 0.387 | 0.421 | 0.328 | 0.342 | 0.361 | 0.314 | -16% (nd) | +1% (nd) |  |
| count_statuses | 1.850 | 1.852 | 2.346 | 2.396 | 2.531 | 2.667 | +40% | +10% (nd) |  |
| count_partitions | 0.133 | 0.140 | 0.213 | 0.246 | 0.147 | 0.139 | +5% (nd) | -38% |  |
| oldest_created | 0.205 | 0.205 | 0.202 | 0.202 | 0.182 | 0.157 | -17% (nd) | -16% (nd) |  |
| oldest_due_strict | 0.421 | 0.432 | 0.449 | 0.450 | 0.484 | 0.480 | +13% | +7% (nd) |  |
| oldest_due_nonstrict | 0.171 | 0.179 | 0.237 | 0.237 | 0.199 | 0.206 | +16% (nd) | -15% (nd) |  |
| count_new | 0.189 | 0.203 | 0.199 | 0.209 | 0.210 | 0.201 | +5% (nd) | +1% (nd) |  |
| count_failed | 1.661 | 1.649 | 1.759 | 1.757 | 1.867 | 1.877 | +13% | +6% (nd) |  |
| count_completed | 90.9 | 90.9 | 98.7 | 99.1 | 98.9 | 100.9 | +10% (nd) | +1% (nd) |  |
| count_partition_single | 0.087 | 0.090 | 0.194 | 0.206 | 0.097 | 0.086 | +3% (nd) | -54% |  |

### S16-S4

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.564 | 0.566 | 0.459 | 0.452 | 0.434 | 0.434 | -23% | -5% (nd) |  |
| keys_nonstrict | 5120.7* | 5107.2* | 0.480 | 0.503 | 0.457 | 0.465 | -100% | -6% (nd) |  |
| pages | 0.332 | 0.330 | 0.345 | 0.336 | 0.335 | 0.323 | -1% (nd) | -3% (nd) |  |
| insert | 0.658 | 0.702 | 0.533 | 0.504 | 0.495 | 0.762 | -8% (nd) | +21% (nd) |  |
| upd_complete | 0.767 | 0.749 | 0.788 | 0.761 | 0.839 | 0.854 | +12% (nd) | +9% (nd) |  |
| upd_retry | 0.346 | 0.337 | 0.428 | 0.420 | 0.425 | 0.439 | +27% (nd) | +2% (nd) |  |
| upd_fail | 0.287 | 0.284 | 0.319 | 0.181 | 0.326 | 0.176 | -12% (nd) | +0% (nd) |  |
| delete_by_id | 0.484 | 0.459 | 0.555 | 0.469 | 0.454 | 0.451 | -4% (nd) | -12% (nd) |  |
| count_statuses | 85.9 | 85.9 | 117.1 | 106.1 | 106.2 | 106.4 | +24% | -5% (nd) |  |
| count_partitions | 7.393 | 7.411 | 106.4 | 93.1 | 7.281 | 7.339 | -1% (nd) | -93% |  |
| oldest_created | 4869.4* | 4813.1* | 5308.2* | 6012.4* | 0.184 | 0.168 | -100% | -100% |  |
| oldest_due_strict | 7254.4* | 7361.4* | 0.285 | 0.293 | 0.282 | 0.272 | -100% | -4% (nd) |  |
| oldest_due_nonstrict | 11.3 | 11.2 | 5323.3* | 5976.0* | 0.224 | 0.209 | -98% | -100% |  |
| count_new | 85.8 | 85.8 | 102.9 | 92.6 | 93.4 | 93.4 | +9% (nd) | -4% (nd) |  |
| count_failed | 0.073 | 0.086 | 0.081 | 0.084 | 0.079 | 0.094 | +9% (nd) | +5% (nd) |  |
| count_completed | 91.8 | 93.0 | 100.4 | 100.7 | 100.1 | 100.3 | +8% (nd) | -0% (nd) |  |
| count_partition_single | 0.495 | 0.500 | 96.2 | 85.6 | 0.489 | 0.472 | -3% (nd) | -99% |  |

### S1

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.976 | 0.964 | 0.648 | 0.631 | 0.611 | 0.618 | -37% | -4% (nd) |  |
| keys_nonstrict | 1.102 | 1.097 | 0.644 | 0.643 | 0.624 | 0.630 | -43% | -3% (nd) |  |
| pages | 0.396 | 0.389 | 0.312 | 0.308 | 0.314 | 0.333 | -18% | +4% (nd) |  |
| insert | 0.236 | 0.235 | 0.204 | 0.212 | 0.232 | 0.301 | +13% (nd) | +28% (nd) |  |
| upd_complete | 0.198 | 0.200 | 0.180 | 0.174 | 0.190 | 0.274 | +17% (nd) | +31% (nd) |  |
| upd_retry | 0.147 | 0.159 | 0.144 | 0.146 | 0.148 | 0.152 | -2% (nd) | +3% (nd) |  |
| upd_fail | 0.155 | 0.164 | 0.146 | 0.140 | 0.158 | 0.171 | +3% (nd) | +15% (nd) |  |
| delete_by_id | 0.089 | 0.088 | 0.073 | 0.077 | 0.075 | 0.088 | -8% (nd) | +9% (nd) |  |
| count_statuses | 0.205 | 0.196 | 0.121 | 0.127 | 0.130 | 0.119 | -38% | +0% (nd) |  |
| count_partitions | 0.378 | 0.382 | 0.297 | 0.309 | 0.316 | 0.303 | -19% | +2% (nd) |  |
| oldest_created | 0.376 | 0.373 | 0.320 | 0.326 | 0.293 | 0.275 | -24% | -12% |  |
| oldest_due_strict | 1.065 | 1.047 | 0.399 | 0.412 | 0.420 | 0.390 | -62% | -0% (nd) |  |
| oldest_due_nonstrict | 0.553 | 0.556 | 0.453 | 0.432 | 0.438 | 0.403 | -24% | -5% (nd) |  |
| count_new | 0.113 | 0.105 | 0.099 | 0.106 | 0.101 | 0.109 | -4% (nd) | +2% (nd) |  |
| count_failed | 0.092 | 0.091 | 0.081 | 0.086 | 0.083 | 0.082 | -10% (nd) | -1% (nd) |  |
| count_completed | 0.992 | 0.991 | 1.341 | 1.346 | 1.359 | 1.371 | +38% | +2% (nd) |  |
| count_partition_single | 0.096 | 0.094 | 0.102 | 0.108 | 0.097 | 0.083 | -5% (nd) | -14% (nd) |  |

### S2

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.214 | 1.237 | 0.642 | 0.630 | 0.614 | 0.610 | -50% | -4% (nd) |  |
| keys_nonstrict | 3.160 | 3.105 | 0.627 | 0.627 | 0.617 | 0.617 | -80% | -2% (nd) |  |
| pages | 0.389 | 0.421 | 0.314 | 0.334 | 0.312 | 0.315 | -23% | -3% (nd) |  |
| insert | 0.518 | 0.954 | 0.404 | 0.411 | 0.410 | 0.396 | -45% (nd) | -1% (nd) |  |
| upd_complete | 0.481 | 0.516 | 0.332 | 0.341 | 0.414 | 0.409 | -17% (nd) | +22% (nd) |  |
| upd_retry | 0.291 | 0.264 | 0.242 | 0.255 | 0.248 | 0.247 | -11% (nd) | -0% (nd) |  |
| upd_fail | 0.299 | 0.280 | 0.191 | 0.273 | 0.192 | 0.264 | -21% (nd) | -2% (nd) |  |
| delete_by_id | 0.482 | 0.517 | 0.321 | 0.346 | 0.336 | 0.338 | -33% (nd) | +1% (nd) |  |
| count_statuses | 1.837 | 1.841 | 2.346 | 2.375 | 2.489 | 2.517 | +36% | +6% (nd) |  |
| count_partitions | 1.428 | 1.440 | 0.411 | 0.455 | 0.454 | 0.447 | -69% | +4% (nd) |  |
| oldest_created | 1.463 | 1.443 | 0.524 | 0.535 | 0.272 | 0.288 | -81% | -47% |  |
| oldest_due_strict | 3.721 | 3.729 | 0.377 | 0.397 | 0.394 | 0.411 | -89% | +4% (nd) |  |
| oldest_due_nonstrict | 0.993 | 0.986 | 0.635 | 0.630 | 0.407 | 0.421 | -58% | -35% |  |
| count_new | 0.187 | 0.187 | 0.206 | 0.221 | 0.205 | 0.212 | +11% (nd) | -2% (nd) |  |
| count_failed | 1.650 | 1.654 | 1.739 | 1.758 | 1.865 | 1.861 | +13% | +7% (nd) |  |
| count_completed | 90.4 | 92.9 | 98.6 | 99.0 | 98.5 | 100.9 | +9% (nd) | +1% (nd) |  |
| count_partition_single | 0.100 | 0.100 | 0.194 | 0.216 | 0.082 | 0.094 | -12% (nd) | -57% |  |

### S4

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.620 | 1.626 | over 10 s | over 10 s | 0.684 | 0.669 | -58% | opt over limit (final 0.68 ms) |  |
| keys_nonstrict | 85130.7* | 84707.1* | over 10 s | over 10 s | 0.675 | 0.680 | -100% | opt over limit (final 0.68 ms) |  |
| pages | 0.346 | 0.355 | 0.357 | 0.353 | 0.327 | 0.328 | -7% (nd) | -8% (nd) |  |
| insert | 0.552 | 0.596 | 0.603 | 0.631 | 0.527 | 0.511 | -10% (nd) | -16% (nd) |  |
| upd_complete | 0.670 | 0.810 | 0.757 | 0.736 | 0.815 | 0.785 | +8% (nd) | +7% (nd) |  |
| upd_retry | 0.392 | 0.398 | 0.444 | 0.480 | 0.431 | 0.427 | +9% (nd) | -7% (nd) |  |
| upd_fail | 0.189 | 0.310 | 0.341 | 0.340 | 0.315 | 0.308 | +25% (nd) | -9% (nd) |  |
| delete_by_id | 0.536 | 0.608 | 0.529 | 0.548 | 0.410 | 0.409 | -28% (nd) | -24% (nd) |  |
| count_statuses | 85.4 | 85.3 | 117.1 | 106.3 | 106.3 | 106.4 | +25% | -5% (nd) |  |
| count_partitions | 358.2 | 357.7 | 128.2 | 108.3 | 118.1 | 119.6 | -67% | +1% (nd) |  |
| oldest_created | 79573.4* | 79538.6* | over 10 s | over 10 s | 0.343 | 0.343 | -100% | opt over limit (final 0.34 ms) |  |
| oldest_due_strict | 19689.0* | 19883.0* | over 10 s | over 10 s | 0.420 | 0.414 | -100% | opt over limit (final 0.42 ms) |  |
| oldest_due_nonstrict | 75326.4* | 411.1 | over 10 s | over 10 s | 0.374 | 0.360 | -100% | opt over limit (final 0.37 ms) | SPREAD>2x mdb-main |
| count_new | 85.4 | 85.3 | 103.0 | 92.3 | 93.2 | 92.9 | +9% (nd) | -5% (nd) |  |
| count_failed | 0.085 | 0.074 | 0.087 | 0.080 | 0.089 | 0.093 | +14% (nd) | +9% (nd) |  |
| count_completed | 92.4 | 92.9 | 100.4 | 100.9 | 100.4 | 100.4 | +8% (nd) | -0% (nd) |  |
| count_partition_single | 0.502 | 0.496 | 96.1 | 84.9 | 0.485 | 0.489 | -2% (nd) | -99% |  |

### S4-b100

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 2.063 | 2.062 | over 10 s | over 10 s | 0.978 | 0.963 | -53% | opt over limit (final 0.97 ms) |  |
| keys_nonstrict | 94953.8* | 95886.9* | over 10 s | over 10 s | 1.099 | 1.087 | -100% | opt over limit (final 1.09 ms) |  |
| pages | 1.401 | 1.384 | 1.282 | 1.246 | 1.160 | 1.116 | -18% | -10% (nd) |  |
| insert | 0.591 | 0.584 | 0.650 | 0.636 | 0.459 | 0.383 | -28% (nd) | -35% (nd) |  |
| upd_complete | 0.738 | 0.800 | 0.737 | 0.762 | 0.775 | 0.633 | -8% (nd) | -6% (nd) |  |
| upd_retry | 0.416 | 0.406 | 0.426 | 0.424 | 0.400 | 0.251 | -21% (nd) | -23% (nd) |  |
| upd_fail | 0.273 | 0.303 | 0.362 | 0.337 | 0.287 | 0.154 | -23% (nd) | -37% (nd) |  |
| delete_by_id | 0.606 | 0.608 | 0.572 | 0.514 | 0.392 | 0.376 | -37% (nd) | -29% (nd) |  |
| count_statuses | 85.3 | 87.2 | 116.9 | 116.4 | 107.6 | 106.2 | +24% | -8% (nd) |  |
| count_partitions | 357.4 | 359.1 | 128.3 | 131.1 | 118.5 | 117.7 | -67% | -9% (nd) |  |
| oldest_created | 89734.3* | 90570.9* | over 10 s | over 10 s | 0.343 | 0.337 | -100% | opt over limit (final 0.34 ms) |  |
| oldest_due_strict | 20955.2* | 20995.2* | over 10 s | over 10 s | 0.413 | 0.417 | -100% | opt over limit (final 0.41 ms) |  |
| oldest_due_nonstrict | 81536.5* | 411.2 | over 10 s | over 10 s | 0.364 | 0.361 | -100% | opt over limit (final 0.36 ms) | SPREAD>2x mdb-main |
| count_new | 85.3 | 87.1 | 103.3 | 102.5 | 93.7 | 92.9 | +8% (nd) | -9% (nd) |  |
| count_failed | 0.081 | 0.082 | 0.071 | 0.072 | 0.087 | 0.088 | +7% (nd) | +22% (nd) |  |
| count_completed | 92.3 | 94.0 | 100.3 | 101.2 | 100.2 | 100.5 | +8% (nd) | -0% (nd) |  |
| count_partition_single | 0.484 | 0.486 | 95.8 | 96.8 | 0.500 | 0.492 | +2% (nd) | -99% |  |

### S5

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 4.631 | 4.759 | over 10 s | over 10 s | 0.625 | 0.625 | -87% | opt over limit (final 0.62 ms) |  |
| keys_nonstrict | 13349.5* | 13593.9* | over 10 s | over 10 s | 0.678 | 0.683 | -100% | opt over limit (final 0.68 ms) |  |
| pages | 4259.8* | 4280.8* | over 10 s | over 10 s | 1427.0 | 1419.5 | -67% | opt over limit (final 1423.24 ms) |  |
| insert | 0.435 | 0.465 | 0.516 | 0.507 | 0.387 | 0.396 | -13% (nd) | -23% (nd) |  |
| upd_complete | 0.766 | 0.787 | 0.642 | 0.628 | 0.629 | 0.670 | -16% (nd) | +2% (nd) |  |
| upd_retry | 0.334 | 0.348 | 0.387 | 0.388 | 0.347 | 0.347 | +2% (nd) | -10% (nd) |  |
| upd_fail | 0.269 | 0.275 | 0.292 | 0.295 | 0.265 | 0.261 | -3% (nd) | -10% (nd) |  |
| delete_by_id | 0.573 | 0.568 | 0.312 | 0.318 | 0.251 | 0.257 | -55% (nd) | -19% (nd) |  |
| count_statuses | 17.3 | 17.3 | 21.5 | 21.6 | 21.4 | 21.5 | +24% | -0% (nd) |  |
| count_partitions | 13503.0* | 13466.4* | 22.4 | 22.5 | 27.2 | 27.0 | -100% | +21% |  |
| oldest_created | 2981.3* | 2838.1* | over 10 s | over 10 s | 0.272 | 0.273 | -100% | opt over limit (final 0.27 ms) |  |
| oldest_due_strict | 3404.7* | 3447.7* | over 10 s | over 10 s | 0.649 | 0.668 | -100% | opt over limit (final 0.66 ms) |  |
| oldest_due_nonstrict | 37.4 | 37.3 | over 10 s | over 10 s | 0.400 | 0.407 | -99% | opt over limit (final 0.40 ms) |  |
| count_new | 17.2 | 17.2 | 19.0 | 19.0 | 18.5 | 18.7 | +8% (nd) | -2% (nd) |  |
| count_failed | 0.089 | 0.079 | 0.089 | 0.093 | 0.079 | 0.093 | +2% (nd) | -5% (nd) |  |
| count_completed | 27.8 | 27.9 | 31.0 | 31.2 | 30.0 | 30.1 | +8% (nd) | -3% (nd) |  |
| count_partition_single | 0.287 | 0.299 | 17.5 | 17.4 | 0.277 | 0.283 | -4% (nd) | -98% |  |

### S6

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 392.4 | 387.3 | over 10 s | over 10 s | 0.836 | 0.822 | -100% | opt over limit (final 0.83 ms) |  |
| keys_nonstrict | 381.0 | 376.2 | over 10 s | over 10 s | 0.840 | 0.831 | -100% | opt over limit (final 0.84 ms) |  |
| pages | 0.344 | 0.352 | over 10 s | over 10 s | 0.323 | 0.313 | -9% (nd) | opt over limit (final 0.32 ms) |  |
| insert | 0.465 | 0.514 | 0.470 | 0.546 | 0.432 | 0.534 | -1% (nd) | -5% (nd) |  |
| upd_complete | 0.450 | 0.499 | 0.459 | 0.513 | 0.547 | 0.608 | +22% (nd) | +19% (nd) |  |
| upd_retry | 0.177 | 0.266 | 0.386 | 0.441 | 0.352 | 0.426 | +76% (nd) | -6% (nd) |  |
| upd_fail | 0.244 | 0.261 | 0.324 | 0.361 | 0.289 | 0.377 | +32% (nd) | -3% (nd) |  |
| delete_by_id | 0.474 | 0.563 | 0.452 | 0.466 | 0.392 | 0.396 | -24% (nd) | -14% (nd) |  |
| count_statuses | 17.3 | 17.2 | 21.8 | 21.4 | 23.5 | 23.2 | +35% | +8% (nd) |  |
| count_partitions | 24.4 | 24.5 | 22.1 | 22.5 | 27.2 | 27.5 | +12% | +23% |  |
| oldest_created | 7248.2* | 7054.0* | over 10 s | over 10 s | 0.288 | 0.285 | -100% | opt over limit (final 0.29 ms) |  |
| oldest_due_strict | 388.5 | 380.5 | over 10 s | over 10 s | 0.481 | 0.480 | -100% | opt over limit (final 0.48 ms) |  |
| oldest_due_nonstrict | 1.736 | 1.791 | over 10 s | over 10 s | 0.424 | 0.398 | -77% | opt over limit (final 0.41 ms) |  |
| count_new | 17.2 | 17.1 | 18.6 | 19.1 | 20.7 | 20.9 | +21% | +10% |  |
| count_failed | 0.090 | 0.090 | 0.102 | 0.095 | 0.077 | 0.095 | -4% (nd) | -13% (nd) |  |
| count_completed | 73.8 | 73.9 | 80.3 | 84.4 | 80.5 | 81.3 | +10% (nd) | -2% (nd) |  |
| count_partition_single | 0.217 | 0.174 | 17.1 | 17.7 | 0.182 | 0.177 | -8% (nd) | -99% |  |

### S7

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.246 | 1.239 | 772.5 | 776.4 | 0.602 | 0.609 | -51% | -100% |  |
| keys_nonstrict | 746.5 | 751.4 | 767.8 | 773.9 | 0.619 | 0.613 | -100% | -100% |  |
| pages | 0.362 | 0.349 | 0.321 | 0.308 | 0.309 | 0.333 | -10% (nd) | +2% (nd) |  |
| insert | 0.473 | 0.493 | 0.436 | 0.418 | 0.396 | 0.408 | -17% (nd) | -6% (nd) |  |
| upd_complete | 0.738 | 0.704 | 0.449 | 0.455 | 0.467 | 0.471 | -35% (nd) | +4% (nd) |  |
| upd_retry | 0.276 | 0.327 | 0.264 | 0.266 | 0.250 | 0.245 | -18% (nd) | -7% (nd) |  |
| upd_fail | 0.270 | 0.277 | 0.305 | 0.290 | 0.294 | 0.283 | +5% (nd) | -3% (nd) |  |
| delete_by_id | 0.498 | 0.467 | 0.399 | 0.401 | 0.326 | 0.326 | -32% (nd) | -19% (nd) |  |
| count_statuses | 48.5 | 48.7 | 69.0 | 68.6 | 68.7 | 68.7 | +41% | -0% (nd) |  |
| count_partitions | 790.8 | 797.3 | 1.385 | 1.405 | 1.699 | 1.740 | -100% | +23% |  |
| oldest_created | 787.6 | 782.9 | 781.8 | 809.5 | 0.272 | 0.286 | -100% | -100% |  |
| oldest_due_strict | 816.7 | 814.3 | 814.8 | 840.7 | 0.398 | 0.400 | -100% | -100% |  |
| oldest_due_nonstrict | 2.672 | 2.707 | 784.3 | 772.6 | 0.412 | 0.423 | -84% | -100% |  |
| count_new | 0.959 | 0.967 | 1.026 | 1.036 | 1.034 | 1.037 | +8% (nd) | +0% (nd) |  |
| count_failed | 47.6 | 47.7 | 54.0 | 54.4 | 53.6 | 54.3 | +13% | -0% (nd) |  |
| count_completed | 56.0 | 56.3 | 60.4 | 61.9 | 60.2 | 60.8 | +8% (nd) | -1% (nd) |  |
| count_partition_single | 0.089 | 0.103 | 0.936 | 0.970 | 0.085 | 0.084 | -12% (nd) | -91% |  |

### S9

| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |
|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 1.246 | - | 0.638 | - | 0.621 | - | -50% | -3% (nd) |  |
| keys_nonstrict | 3.155 | - | 0.645 | - | 0.630 | - | -80% | -2% (nd) |  |
| pages | 0.389 | - | 0.330 | - | 0.309 | - | -21% | -6% (nd) |  |
| insert | 0.408 | - | 0.331 | - | 0.346 | - | -15% (nd) | +5% (nd) |  |
| upd_complete | 0.378 | - | 0.440 | - | 0.463 | - | +22% (nd) | +5% (nd) |  |
| upd_retry | 0.226 | - | 0.151 | - | 0.163 | - | -28% (nd) | +8% (nd) |  |
| upd_fail | 0.247 | - | 0.316 | - | 0.306 | - | +24% (nd) | -3% (nd) |  |
| delete_by_id | 0.705 | - | 0.655 | - | 0.630 | - | -11% (nd) | -4% (nd) |  |
| count_statuses | 3.113 | - | 4.046 | - | 4.055 | - | +30% | +0% (nd) |  |
| count_partitions | 1.508 | - | 0.465 | - | 0.456 | - | -70% | -2% (nd) |  |
| oldest_created | 1.507 | - | 0.537 | - | 0.285 | - | -81% | -47% |  |
| oldest_due_strict | 3.809 | - | 0.410 | - | 0.388 | - | -90% | -5% (nd) |  |
| oldest_due_nonstrict | 1.032 | - | 0.636 | - | 0.408 | - | -60% | -36% |  |
| count_new | 0.186 | - | 0.212 | - | 0.214 | - | +15% (nd) | +1% (nd) |  |
| count_failed | 2.927 | - | 3.104 | - | 3.112 | - | +6% (nd) | +0% (nd) |  |
| count_completed | 123.3 | - | 86.9 | - | 83.4 | - | -32% | -4% (nd) |  |
| count_partition_single | 0.102 | - | 0.226 | - | 0.082 | - | -20% (nd) | -64% |  |

## 2. Plans (pass 1, `plans/*.summary.txt`; `handlers.csv` next to them)

Every query of `mdb-final` above 5 ms (pass 1 median) and every regression of final vs main (change beyond the threshold and slower), with the `mdb-final` and the `mdb-main` plan summary.

#### S8-S2 / count_statuses: final 2.50 ms, main 1.842 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 2.442   cost: 22.287
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=27224 r_rows=14000 r_index_rows=- examined=14000 filtered_est=99.838 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 14000   rows_returned: 2
```

#### S8-S2 / count_failed: final 1.83 ms, main 1.645 ms (regression vs main)

`mdb-final`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.819   cost: 3.965
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=26024 r_rows=12800 r_index_rows=- examined=12800 filtered_est=99.831 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

`mdb-main`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.622   cost: 4.026
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=26586 r_rows=12800 r_index_rows=- examined=12800 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

#### S8-S2 / count_completed: final 98.60 ms, main 90.6 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 104.229   cost: 131.490
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=865284 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 96.314   cost: 133.552
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=881765 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

#### S8-S4 / count_statuses: final 106.93 ms, main 85.5 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 113.325   cost: 1608.673
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1927973 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=99.921 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S8-S4 / count_partitions: final 29.32 ms, main 30.0 ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 30.719   cost: 76.584
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=507468 r_rows=250815 r_index_rows=- examined=250815 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 250815   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 31.109   cost: 76.801
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=509106 r_rows=250815 r_index_rows=- examined=250815 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 250815   rows_returned: 1
```

#### S8-S4 / count_new: final 93.30 ms, main 85.4 ms

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 99.384   cost: 291.086
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1927972 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=99.921 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 91.455   cost: 290.470
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1928214 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S8-S4 / count_completed: final 100.53 ms, main 92.1 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 107.103   cost: 294.322
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1949406 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 98.424   cost: 297.303
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1973573 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S8-S5 / keys_strict: final 53.57 ms, main 13207.7* ms

`mdb-final`:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: 10   rows_returned: 9
r_total_time_ms: 54.125   cost: 61.798
select#1 table=o access=ref key=idx_outbox_record_status_completed_created_id key_parts=[status,completed_at] key_len=87 loops=1 rows_est=394855 r_rows=199980 r_index_rows=- examined=199980 filtered_est=9.669 r_filtered=0.005 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
select#3 table=e access=ref key=idx_outbox_record_key_completed_created_id key_parts=[record_key,completed_at] key_len=1027 loops=17 rows_est=1 r_rows=1 r_index_rows=- examined=17 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
rows_examined_total: 199997   rows_returned: 9
```

`mdb-main`:
```
id: keys_strict   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 9
r_total_time_ms: 12930.277   cost: 69.288
select#1 table=o access=range key=idx_outbox_record_status_created key_parts=[status] key_len=82 loops=1 rows_est=427746 r_rows=199980 r_index_rows=- examined=199980 filtered_est=8.906 r_filtered=0.005 index_condition=no attached_condition=yes using_index=no rowid_filter=no
select#2 table=older access=ref key=idx_outbox_record_record_key_completed_created key_parts=[record_key,completed_at] key_len=1027 loops=33992 rows_est=1 r_rows=1.529 r_index_rows=- examined=51983.000 filtered_est=100 r_filtered=65.373 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
rows_examined_total: 251963.000   rows_returned: 9
```

#### S8-S5 / pages: final 1246.81 ms, main 4019.8* ms

`mdb-final`:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 18000
r_total_time_ms: 1271.396   cost: 89.630
select#1 table=outbox_record access=range key=idx_outbox_record_key_completed_created_id key_parts=[record_key,completed_at] key_len=1027 loops=1 rows_est=36004 r_rows=18000 r_index_rows=18000 examined=18000 filtered_est=100 r_filtered=100 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 18000   rows_returned: 18000
```

`mdb-main`:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 18000
r_total_time_ms: 3980.940   cost: 167.716
select#1 table=outbox_record access=ALL key=- key_parts=[-] key_len=- loops=1 rows_est=447502 r_rows=500000 r_index_rows=- examined=500000 filtered_est=19.856 r_filtered=3.600 index_condition=no attached_condition=yes using_index=no rowid_filter=no
filesort: yes   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 500000   rows_returned: 18000
```

#### S8-S5 / count_statuses: final 21.67 ms, main 17.2 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 22.872   cost: 314.746
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=379088 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 2
```

#### S8-S5 / count_new: final 18.86 ms, main 17.1 ms (regression vs main)

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 20.074   cost: 57.869
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=379068 r_rows=199980 r_index_rows=- examined=199980 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 18.392   cost: 58.212
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=383994 r_rows=199980 r_index_rows=- examined=199980 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

#### S8-S5 / count_completed: final 30.57 ms, main 27.8 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 32.289   cost: 60.279
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=394855 r_rows=300000 r_index_rows=- examined=300000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 300000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 29.669   cost: 67.839
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=447502 r_rows=300000 r_index_rows=- examined=300000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 300000   rows_returned: 1
```

#### S8-S6 / count_statuses: final 23.53 ms, main 17.4 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 24.449   cost: 338.816
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=408249 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

#### S8-S6 / count_partitions: final 6.18 ms, main 6.328 ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 6.233   cost: 7.583
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=50229 r_rows=50229 r_index_rows=- examined=50229 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 50229   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 6.336   cost: 7.559
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=50229 r_rows=50229 r_index_rows=- examined=50229 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 50229   rows_returned: 1
```

#### S8-S6 / count_new: final 20.79 ms, main 17.3 ms (regression vs main)

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 22.289   cost: 62.064
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=408248 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 18.497   cost: 57.285
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=379940 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

#### S8-S6 / count_completed: final 80.01 ms, main 74.8 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 85.756   cost: 134.584
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=886922 r_rows=800000 r_index_rows=- examined=800000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 800000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 79.359   cost: 153.012
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1014843 r_rows=800000 r_index_rows=- examined=800000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 800000   rows_returned: 1
```

#### S8-S7 / count_statuses: final 68.95 ms, main 48.4 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 70.954   cost: 640.728
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=770310 r_rows=400000 r_index_rows=- examined=400000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 400000   rows_returned: 2
```

#### S8-S7 / count_failed: final 53.80 ms, main 47.4 ms (regression vs main)

`mdb-final`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 56.239   cost: 113.437
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=749220 r_rows=390000 r_index_rows=- examined=390000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 390000   rows_returned: 1
```

`mdb-main`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 49.531   cost: 123.143
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=814902 r_rows=390000 r_index_rows=- examined=390000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 390000   rows_returned: 1
```

#### S8-S7 / count_completed: final 60.20 ms, main 55.7 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 64.153   cost: 149.376
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=988044 r_rows=600000 r_index_rows=- examined=600000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 600000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 58.981   cost: 141.236
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=934636 r_rows=600000 r_index_rows=- examined=600000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 600000   rows_returned: 1
```

#### S16-S2 / keys_nonstrict: final 0.67 ms, main 0.405 ms (regression vs main)

`mdb-final`:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: 10   rows_returned: 10
r_total_time_ms: 0.317   cost: 0.140
select#1 table=o access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=62 r_rows=62 r_index_rows=62 examined=62 filtered_est=100 r_filtered=85.484 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
select#3 table=e access=ref key=idx_outbox_record_key_completed_created_id key_parts=[record_key,completed_at] key_len=1027 loops=53 rows_est=1 r_rows=1.019 r_index_rows=1.019 examined=54.000 filtered_est=0.114 r_filtered=98.148 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
rows_examined_total: 116.000   rows_returned: 10
```

`mdb-main`:
```
id: keys_nonstrict   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 10
r_total_time_ms: 0.134   cost: 0.123
select#1 table=o access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status,next_retry_at] key_len=90 loops=1 rows_est=53 r_rows=53 r_index_rows=53 examined=53 filtered_est=100 r_filtered=100 index_condition=yes attached_condition=no using_index=no rowid_filter=no
filesort: yes   temporary_table: yes   duplicates_removal: no   strategies: -
rows_examined_total: 53   rows_returned: 10
```

#### S16-S2 / count_statuses: final 2.53 ms, main 1.850 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 2.450   cost: 22.285
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=27224 r_rows=14000 r_index_rows=- examined=14000 filtered_est=99.838 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 14000   rows_returned: 2
```

#### S16-S2 / oldest_due_strict: final 0.48 ms, main 0.421 ms (regression vs main)

`mdb-final`:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: 1   rows_returned: 1
r_total_time_ms: 0.267   cost: 0.140
select#1 table=o access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=62 r_rows=62 r_index_rows=62 examined=62 filtered_est=100 r_filtered=83.871 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
select#3 table=earlier access=ref key=idx_outbox_record_key_completed_created_id key_parts=[record_key,completed_at] key_len=1027 loops=53 rows_est=1 r_rows=1 r_index_rows=- examined=53 filtered_est=100 r_filtered=1.887 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: yes   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
rows_examined_total: 115   rows_returned: 1
```

`mdb-main`:
```
id: oldest_due_strict   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 0.249   cost: 0.123
select#1 table=o access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status,next_retry_at] key_len=90 loops=1 rows_est=53 r_rows=53 r_index_rows=53 examined=53 filtered_est=100 r_filtered=98.113 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
select#2 table=older access=ref key=idx_outbox_record_record_key_completed_created key_parts=[record_key,completed_at] key_len=1027 loops=53 rows_est=1 r_rows=1 r_index_rows=- examined=53 filtered_est=100 r_filtered=1.887 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: dependent-subquery,subquery-cache
rows_examined_total: 106   rows_returned: 1
```

#### S16-S2 / count_failed: final 1.87 ms, main 1.661 ms (regression vs main)

`mdb-final`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.836   cost: 3.963
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=26024 r_rows=12800 r_index_rows=- examined=12800 filtered_est=99.831 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

`mdb-main`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.633   cost: 4.017
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=26586 r_rows=12800 r_index_rows=- examined=12800 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

#### S16-S2 / count_completed: final 98.85 ms, main 90.9 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 105.043   cost: 132.685
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=873524 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 96.376   cost: 145.077
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=960053 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

#### S16-S4 / count_statuses: final 106.20 ms, main 85.9 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 112.719   cost: 1499.967
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1796354 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S16-S4 / count_partitions: final 7.28 ms, main 7.393 ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 7.477   cost: 19.102
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=126278 r_rows=62536 r_index_rows=- examined=62536 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 62536   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 7.568   cost: 19.131
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=126816 r_rows=62536 r_index_rows=- examined=62536 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 62536   rows_returned: 1
```

#### S16-S4 / count_new: final 93.44 ms, main 85.8 ms

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 100.573   cost: 271.843
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1796354 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 91.234   cost: 290.470
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1928214 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S16-S4 / count_completed: final 100.11 ms, main 91.8 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 106.279   cost: 271.843
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1796354 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 98.159   cost: 298.517
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1981628 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S1 / count_completed: final 1.36 ms, main 0.992 ms (regression vs main)

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.305   cost: 1.408
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_created_id key_parts=[status] key_len=82 loops=1 rows_est=8853 r_rows=9800 r_index_rows=- examined=9800 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 9800   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 0.960   cost: 1.312
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=8200 r_rows=9800 r_index_rows=- examined=9800 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 9800   rows_returned: 1
```

#### S2 / count_statuses: final 2.49 ms, main 1.837 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 2.446   cost: 22.292
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=27224 r_rows=14000 r_index_rows=- examined=14000 filtered_est=99.838 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 14000   rows_returned: 2
```

#### S2 / count_failed: final 1.86 ms, main 1.650 ms (regression vs main)

`mdb-final`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.830   cost: 3.970
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=26024 r_rows=12800 r_index_rows=- examined=12800 filtered_est=99.831 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

`mdb-main`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 1.643   cost: 4.017
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=26586 r_rows=12800 r_index_rows=- examined=12800 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 12800   rows_returned: 1
```

#### S2 / count_completed: final 98.53 ms, main 90.4 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 104.912   cost: 126.649
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=832320 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 96.682   cost: 143.209
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=947692 r_rows=986000 r_index_rows=- examined=986000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 986000   rows_returned: 1
```

#### S4 / count_statuses: final 106.26 ms, main 85.4 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 112.540   cost: 1285.078
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1538581 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4 / count_partitions: final 118.07 ms, main 358.2 ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 124.565   cost: 8.884
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=58368 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 366.934   cost: 11.005
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=70656 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4 / count_new: final 93.19 ms, main 85.4 ms

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 99.373   cost: 234.117
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1538581 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 92.400   cost: 280.710
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1860797 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4 / count_completed: final 100.45 ms, main 92.4 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 105.965   cost: 234.117
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1538581 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 99.304   cost: 280.710
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1860797 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4-b100 / count_statuses: final 107.58 ms, main 85.3 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 112.888   cost: 1426.096
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1707744 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4-b100 / count_partitions: final 118.47 ms, main 357.4 ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 124.952   cost: 9.822
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status,partition_no] key_len=86 loops=1 rows_est=64768 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 361.544   cost: 11.306
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=72704 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4-b100 / count_new: final 93.67 ms, main 85.3 ms

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 99.398   cost: 258.889
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1707744 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 91.415   cost: 289.063
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1917185 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S4-b100 / count_completed: final 100.15 ms, main 92.3 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 106.164   cost: 258.889
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=1707744 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 98.792   cost: 289.063
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1917185 r_rows=1000000 r_index_rows=- examined=1000000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1000000   rows_returned: 1
```

#### S5 / pages: final 1426.97 ms, main 4259.8* ms

`mdb-final`:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 20000
r_total_time_ms: 1428.515   cost: 98.415
select#1 table=outbox_record access=range key=idx_outbox_record_key_completed_created_id key_parts=[record_key,completed_at] key_len=1027 loops=1 rows_est=40012 r_rows=20000 r_index_rows=20000 examined=20000 filtered_est=95.577 r_filtered=100 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 20000   rows_returned: 20000
```

`mdb-main`:
```
id: pages   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 20000
r_total_time_ms: 4223.097   cost: 174.631
select#1 table=outbox_record access=ALL key=- key_parts=[-] key_len=- loops=1 rows_est=445478 r_rows=500000 r_index_rows=- examined=500000 filtered_est=22.352 r_filtered=4 index_condition=no attached_condition=yes using_index=no rowid_filter=no
filesort: yes   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 500000   rows_returned: 20000
```

#### S5 / count_statuses: final 21.45 ms, main 17.3 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 22.714   cost: 314.583
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=379088 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 2
```

#### S5 / count_partitions: final 27.16 ms, main 13503.0* ms

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 28.246   cost: 62.673
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=410758 r_rows=199980 r_index_rows=- examined=199980 filtered_est=99.415 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 13403.285   cost: 416.989
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=383994 r_rows=199980 r_index_rows=199980 examined=199980 filtered_est=100 r_filtered=100 index_condition=yes attached_condition=yes using_index=no rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

#### S5 / count_new: final 18.54 ms, main 17.2 ms

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 19.696   cost: 57.707
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=379068 r_rows=199980 r_index_rows=- examined=199980 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 18.464   cost: 58.245
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=383994 r_rows=199980 r_index_rows=- examined=199980 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 199980   rows_returned: 1
```

#### S5 / count_completed: final 30.01 ms, main 27.8 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 32.197   cost: 65.043
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=427253 r_rows=300000 r_index_rows=- examined=300000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 300000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 29.698   cost: 67.572
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=445478 r_rows=300000 r_index_rows=- examined=300000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 300000   rows_returned: 1
```

#### S6 / count_statuses: final 23.50 ms, main 17.3 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 24.376   cost: 338.816
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=408249 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

#### S6 / count_partitions: final 27.19 ms, main 24.4 ms (regression vs main)

`mdb-final`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 28.340   cost: 62.064
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=408248 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

`mdb-main`:
```
id: count_partitions   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 25.549   cost: 74.071
select#1 table=outbox_record access=range key=idx_outbox_record_partition_status_retry key_parts=[partition_no,status] key_len=86 loops=1 rows_est=487168 r_rows=200000 r_index_rows=- examined=200000 filtered_est=77.990 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

#### S6 / count_new: final 20.71 ms, main 17.2 ms (regression vs main)

`mdb-final`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 21.803   cost: 62.064
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=408248 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

`mdb-main`:
```
id: count_new   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 18.447   cost: 57.338
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=379940 r_rows=200000 r_index_rows=- examined=200000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 200000   rows_returned: 1
```

#### S6 / count_completed: final 80.55 ms, main 73.8 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 85.785   cost: 135.178
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=891186 r_rows=800000 r_index_rows=- examined=800000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 800000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 78.588   cost: 147.364
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=976467 r_rows=800000 r_index_rows=- examined=800000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 800000   rows_returned: 1
```

#### S7 / count_statuses: final 68.72 ms, main 48.5 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 71.257   cost: 641.205
select#1 table=outbox_record access=range key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=770310 r_rows=400000 r_index_rows=- examined=400000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 400000   rows_returned: 2
```

#### S7 / count_failed: final 53.65 ms, main 47.6 ms (regression vs main)

`mdb-final`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 55.700   cost: 113.901
select#1 table=outbox_record access=ref key=idx_outbox_record_status_completed_retry key_parts=[status] key_len=82 loops=1 rows_est=749220 r_rows=390000 r_index_rows=- examined=390000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 390000   rows_returned: 1
```

`mdb-main`:
```
id: count_failed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 49.357   cost: 122.867
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=814902 r_rows=390000 r_index_rows=- examined=390000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 390000   rows_returned: 1
```

#### S7 / count_completed: final 60.21 ms, main 56.0 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 63.911   cost: 133.720
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=881228 r_rows=600000 r_index_rows=- examined=600000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 600000   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 59.168   cost: 154.340
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1023649 r_rows=600000 r_index_rows=- examined=600000 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 600000   rows_returned: 1
```

#### S9 / count_statuses: final 4.05 ms, main 3.113 ms (regression vs main)

`mdb-final`:
```
id: count_statuses   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 2
r_total_time_ms: 4.072   cost: 34.699
select#1 table=outbox_record access=range key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=42296 r_rows=24027 r_index_rows=- examined=24027 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 24027   rows_returned: 2
```

#### S9 / count_completed: final 83.43 ms, main 123.3 ms

`mdb-final`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 88.622   cost: 115.450
select#1 table=outbox_record access=ref key=idx_outbox_record_status_partition_created key_parts=[status] key_len=82 loops=1 rows_est=762898 r_rows=829213 r_index_rows=- examined=829213 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 829213   rows_returned: 1
```

`mdb-main`:
```
id: count_completed   mode: ANALYZE (executed)   sql_select_limit: none   rows_returned: 1
r_total_time_ms: 132.507   cost: 181.344
select#1 table=outbox_record access=ref key=idx_outbox_record_status_retry key_parts=[status] key_len=82 loops=1 rows_est=1196664 r_rows=1298213 r_index_rows=- examined=1298213 filtered_est=100 r_filtered=100 index_condition=no attached_condition=yes using_index=yes rowid_filter=no
filesort: no   temporary_table: no   duplicates_removal: no   strategies: -
rows_examined_total: 1298213   rows_returned: 1
```

## 3. Write cost (writecost, solo cells; S2-wc and S4-wc, both passes)

### S2-wc: per phase redo B/write / pages/write / p50 ms / p95 ms / throughput per s

| run | insert_1thread | insert_4threads | upd_complete_committed | upd_retry_committed | upd_fail_committed | delete_by_id_committed |
|---|---|---|---|---|---|---|
| mdb-main pass1 | 2484 / 1.37 / 0.67 / 0.84 / 1422 | 2311 / 1.30 / 1.15 / 1.83 / 3160 | 5694 / 2.80 / 0.68 / 0.96 / 109 | 2895 / 3.40 / 0.60 / 0.76 / 151 | 4630 / 3.50 / 0.61 / 0.75 / 150 | 2816 / 5.11 / 0.54 / 0.70 / 152 |
| mdb-main pass2 | 2481 / 1.35 / 0.66 / 0.87 / 1417 | 2277 / 1.29 / 1.16 / 1.85 / 3148 | 5704 / 2.82 / 0.65 / 0.92 / 109 | 2693 / 3.55 / 0.61 / 0.76 / 150 | 4548 / 3.50 / 0.63 / 0.73 / 151 | 2772 / 5.10 / 0.53 / 0.63 / 152 |
| mdb-opt pass1 | 2365 / 1.25 / 0.66 / 0.81 / 1440 | 2251 / 1.25 / 1.13 / 1.82 / 3187 | 5195 / 2.41 / 0.65 / 0.92 / 108 | 2986 / 2.39 / 0.64 / 0.78 / 150 | 4119 / 2.97 / 0.65 / 0.86 / 150 | 1824 / 3.62 / 0.56 / 0.66 / 152 |
| mdb-opt pass2 | 2272 / 1.24 / 0.66 / 0.81 / 1444 | 2387 / 1.27 / 1.17 / 1.87 / 3100 | 4700 / 3.12 / 0.63 / 0.84 / 108 | 2904 / 2.38 / 0.64 / 0.80 / 150 | 4129 / 2.95 / 0.64 / 0.82 / 150 | 2005 / 3.62 / 0.56 / 0.64 / 152 |
| mdb-final pass1 | 2314 / 1.24 / 0.69 / 0.87 / 1066 | 2360 / 1.25 / 1.16 / 1.86 / 3115 | 8807 / 3.70 / 0.72 / 1.05 / 143 | 3302 / 2.41 / 0.64 / 0.79 / 150 | 3968 / 3.01 / 0.66 / 0.92 / 149 | 5722 / 3.21 / 0.57 / 0.71 / 152 |
| mdb-final pass2 | 2388 / 1.29 / 0.66 / 0.81 / 1438 | 2339 / 1.27 / 1.16 / 1.83 / 3128 | 9451 / 4.90 / 0.69 / 0.98 / 101 | 3103 / 2.39 / 0.65 / 0.78 / 150 | 4027 / 3.01 / 0.64 / 0.81 / 150 | 5647 / 5.34 / 0.55 / 0.64 / 152 |

Changes (mean of both passes) final vs main / final vs opt: redo B/write, p50 ms (thresholds redo 12 %, p50 8 %, p95 25 %):

- insert_1thread: redo -5% (nd) vs main, +1% (nd) vs opt; p50 +2% (nd) vs main, +3% (nd) vs opt
- insert_4threads: redo +2% (nd) vs main, +1% (nd) vs opt; p50 +1% (nd) vs main, +1% (nd) vs opt
- upd_complete_committed: redo +60% vs main, +85% vs opt; p50 +6% (nd) vs main, +11% vs opt
- upd_retry_committed: redo +15% vs main, +9% (nd) vs opt; p50 +6% (nd) vs main, +1% (nd) vs opt
- upd_fail_committed: redo -13% vs main, -3% (nd) vs opt; p50 +4% (nd) vs main, +1% (nd) vs opt
- delete_by_id_committed: redo +103% vs main, +197% vs opt; p50 +3% (nd) vs main, -1% (nd) vs opt

Index sizes MB before -> after the cell, and all-phase redo:

- mdb-main pass1: PRIMARY 1362->1374, partition_status_retry 66->70, record_key_completed_created 95->97, record_key_created 90->90, status_created 62->63, status_retry 62->63, index total 374->383 MB, index count 6, all-phase redo 29 MB
- mdb-main pass2: PRIMARY 1362->1374, partition_status_retry 66->70, record_key_completed_created 95->97, record_key_created 90->90, status_created 62->63, status_retry 62->63, index total 374->383 MB, index count 6, all-phase redo 29 MB
- mdb-opt pass1: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_partition 67->69, status_completed_retry 71->72, index total 348->354 MB, index count 5, all-phase redo 28 MB
- mdb-opt pass2: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_partition 67->69, status_completed_retry 71->72, index total 348->354 MB, index count 5, all-phase redo 28 MB
- mdb-final pass1: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_retry 71->72, status_partition_created 66->70, index total 347->355 MB, index count 5, all-phase redo 31 MB
- mdb-final pass2: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_retry 71->72, status_partition_created 66->70, index total 347->355 MB, index count 5, all-phase redo 31 MB

### S4-wc: per phase redo B/write / pages/write / p50 ms / p95 ms / throughput per s

| run | insert_1thread | insert_4threads | upd_complete_committed | upd_retry_committed | upd_fail_committed | delete_by_id_committed |
|---|---|---|---|---|---|---|
| mdb-main pass1 | 2598 / 1.47 / 0.69 / 0.91 / 1371 | 2436 / 1.33 / 1.17 / 1.95 / 3057 | 14346 / 7.39 / 0.97 / 1.62 / 470 | 3604 / 1.78 / 0.67 / 0.86 / 592 | 3874 / 3.01 / 0.71 / 0.95 / 647 | 1679 / 2.37 / 0.70 / 0.86 / 755 |
| mdb-main pass2 | 2551 / 1.44 / 0.72 / 1.01 / 886 | 2417 / 1.32 / 1.19 / 1.96 / 3027 | 14307 / 8.01 / 0.96 / 1.93 / 473 | 3659 / 1.98 / 0.67 / 0.88 / 771 | 3919 / 2.82 / 0.71 / 0.95 / 648 | 1654 / 2.23 / 0.70 / 0.87 / 756 |
| mdb-opt pass1 | 5760 / 2.55 / 0.71 / 0.96 / 1299 | 3162 / 2.16 / 1.31 / 2.43 / 2590 | 14537 / 6.05 / 0.92 / 1.35 / 51 | 14332 / 3.45 / 0.78 / 1.07 / 451 | 4140 / 1.61 / 0.64 / 0.84 / 602 | 1832 / 1.00 / 0.61 / 0.75 / 817 |
| mdb-opt pass2 | 5649 / 2.62 / 0.71 / 0.97 / 1296 | 3160 / 2.18 / 1.30 / 2.44 / 2603 | 14602 / 5.95 / 0.91 / 1.38 / 49 | 14219 / 3.40 / 0.78 / 1.03 / 356 | 4183 / 1.64 / 0.64 / 0.85 / 441 | 1816 / 0.92 / 0.61 / 0.76 / 702 |
| mdb-final pass1 | 2566 / 1.41 / 0.70 / 0.93 / 1186 | 2421 / 1.32 / 1.16 / 1.90 / 3094 | 14513 / 8.38 / 0.98 / 2.22 / 517 | 14254 / 3.66 / 0.79 / 1.06 / 617 | 4125 / 2.81 / 0.72 / 0.98 / 642 | 2100 / 0.96 / 0.61 / 0.75 / 817 |
| mdb-final pass2 | 2612 / 1.40 / 0.69 / 0.89 / 1376 | 2408 / 1.32 / 1.18 / 1.95 / 3019 | 14474 / 7.98 / 0.98 / 1.88 / 474 | 14315 / 3.56 / 0.79 / 1.06 / 618 | 4139 / 2.91 / 0.72 / 0.96 / 571 | 2094 / 1.20 / 0.61 / 0.79 / 808 |

Changes (mean of both passes) final vs main / final vs opt: redo B/write, p50 ms (thresholds redo 12 %, p50 8 %, p95 25 %):

- insert_1thread: redo +1% (nd) vs main, -55% vs opt; p50 -1% (nd) vs main, -3% (nd) vs opt
- insert_4threads: redo -0% (nd) vs main, -24% vs opt; p50 -1% (nd) vs main, -10% vs opt
- upd_complete_committed: redo +1% (nd) vs main, -1% (nd) vs opt; p50 +2% (nd) vs main, +8% (nd) vs opt
- upd_retry_committed: redo +293% vs main, +0% (nd) vs opt; p50 +17% vs main, +1% (nd) vs opt
- upd_fail_committed: redo +6% (nd) vs main, -1% (nd) vs opt; p50 +1% (nd) vs main, +12% vs opt
- delete_by_id_committed: redo +26% vs main, +15% vs opt; p50 -13% vs main, +0% (nd) vs opt

Index sizes MB before -> after the cell, and all-phase redo:

- mdb-main pass1: PRIMARY 2656->2669, partition_status_retry 122->131, record_key_completed_created 181->196, record_key_created 175->176, status_created 113->114, status_retry 113->114, index total 704->731 MB, index count 6, all-phase redo 143 MB
- mdb-main pass2: PRIMARY 2656->2668, partition_status_retry 122->131, record_key_completed_created 181->198, record_key_created 175->176, status_created 113->115, status_retry 113->114, index total 704->734 MB, index count 6, all-phase redo 143 MB
- mdb-opt pass1: PRIMARY 2656->2669, key_completed_created_id 181->199, status_completed_created_id 212->229, status_completed_partition 119->170, status_completed_retry 127->130, index total 640->727 MB, index count 5, all-phase redo 219 MB
- mdb-opt pass2: PRIMARY 2656->2669, key_completed_created_id 181->197, status_completed_created_id 212->232, status_completed_partition 119->170, status_completed_retry 127->130, index total 640->728 MB, index count 5, all-phase redo 218 MB
- mdb-final pass1: PRIMARY 2656->2668, key_completed_created_id 181->197, status_completed_created_id 212->226, status_completed_retry 127->129, status_partition_created 122->130, index total 642->682 MB, index count 5, all-phase redo 200 MB
- mdb-final pass2: PRIMARY 2656->2669, key_completed_created_id 181->199, status_completed_created_id 212->226, status_completed_retry 127->129, status_partition_created 122->130, index total 642->684 MB, index count 5, all-phase redo 200 MB

## 4. Concurrency (S10; 60 s per cell, both passes; HLL end = Innodb_history_list_length at the end)

| label | run | processed/s p1 | processed/s p2 | inserted/s p1 | inserted/s p2 | keys p50 p1/p2 | pages p50 p1/p2 | upd p50 p1/p2 | redo MB p1/p2 | HLL end p1/p2 |
|---|---|---|---|---|---|---|---|---|---|---|
| S10-S2-p1 | mdb-main | 513.1 | 513.1 | 497.8 | 498.0 | 1.30/1.27 | 0.12/0.11 | 0.79/0.60 | 186/180 | 923/537 |
| S10-S2-p1 | mdb-opt | 513.9 | 513.8 | 498.2 | 498.1 | 0.57/0.57 | 0.18/0.18 | 0.97/0.60 | 182/182 | 490/949 |
| S10-S2-p1 | mdb-final | 513.3 | 513.1 | 498.2 | 497.4 | 0.53/0.53 | 0.18/0.18 | 0.63/0.60 | 183/183 | 786/340 |
| S10-S2-p1-unthr | mdb-main | 358.6 | 387.8 | 2324.9 | 2517.4 | 7.35/7.39 | 0.21/0.21 | 1.18/1.18 | 458/498 | 647/505 |
| S10-S2-p1-unthr | mdb-opt | 2.3 | 2.4 | 2466.5 | 2018.5 | 0.78/0.56 | 3.83/3.58 | 1.10/1.10 | 402/335 | 157335/132750 |
| S10-S2-p1-unthr | mdb-final | 402.5 | 464.6 | 2263.2 | 2620.8 | 0.58/0.58 | 5.48/5.53 | 1.15/1.17 | 451/524 | 2328/197 |
| S10-S2-p4 | mdb-main | 512.7 | 512.8 | 497.4 | 497.6 | 0.52/0.52 | 0.17/0.17 | 0.94/0.78 | 184/181 | 249/69 |
| S10-S2-p4 | mdb-opt | 512.8 | 513.0 | 497.5 | 497.6 | 0.41/0.40 | 0.17/0.17 | 1.01/0.73 | 183/184 | 280/208 |
| S10-S2-p4 | mdb-final | 512.6 | 512.7 | 497.4 | 497.5 | 0.40/0.38 | 0.17/0.17 | 1.07/0.86 | 183/182 | 35/478 |
| S10-S2-p4-unthr | mdb-main | 1062.9 | 934.5 | 2364.2 | 2069.7 | 9.25/8.89 | 0.25/0.24 | 1.22/1.22 | 634/552 | 1472/9 |
| S10-S2-p4-unthr | mdb-opt | 1366.9 | 1327.5 | 2235.8 | 2186.6 | 0.97/1.01 | 2.64/2.34 | 1.22/1.22 | 670/649 | 2740/3772 |
| S10-S2-p4-unthr | mdb-final | 1295.7 | 1309.3 | 2145.0 | 2141.1 | 0.98/0.96 | 2.56/2.58 | 1.22/1.22 | 632/640 | 3789/3472 |
| S10-S4-p4 | mdb-main | 768.9 | 784.1 | 498.0 | 497.9 | 10.52/10.48 | 4.68/4.58 | 1.76/1.60 | 517/487 | 16426/18403 |
| S10-S4-p4 | mdb-opt | 925.0 | 902.6 | 456.7 | 486.6 | 9.78/10.16 | 0.82/0.83 | 1.55/1.70 | 566/586 | 36981/33294 |
| S10-S4-p4 | mdb-final | 888.0 | 917.6 | 498.0 | 498.0 | 9.64/9.55 | 0.88/0.85 | 1.72/1.55 | 546/509 | 24475/28888 |
| S10-S4-p4-unthr | mdb-main | 405.0 | 393.3 | 1497.9 | 1473.9 | 20.86/21.17 | 6.58/6.72 | 2.61/2.69 | 542/539 | 16119/14095 |
| S10-S4-p4-unthr | mdb-opt | 576.4 | 573.8 | 1413.8 | 1399.8 | 16.24/16.45 | 1.26/1.25 | 2.33/2.34 | 527/530 | 49644/48656 |
| S10-S4-p4-unthr | mdb-final | 579.0 | 565.7 | 1623.9 | 1626.1 | 16.06/16.52 | 1.44/1.40 | 2.42/2.43 | 583/575 | 39927/40367 |

## 5. S9 (churn, pass 1 only, retention cap 2700 s)

### S9 churn (pass 1)

| run | removed_by_retention | retention capped at s | churn s | table_rows before->after | data MB before->after | index MB before->after | file MB before->after | HLL after | redo MB (global delta) |
|---|---|---|---|---|---|---|---|---|---|
| mdb-main | 31000 | 2700 | 2890 | 877645->1196664 | 1362->1722 | 374->556 | 1741->2366 | 1 | 2509 |
| mdb-opt | 500000 | no | 303 | 902367->700444 | 1362->1751 | 348->421 | 1715->2315 | 0 | 3247 |
| mdb-final | 500000 | no | 294 | 976534->762898 | 1362->1748 | 347->444 | 1715->2319 | 3 | 3227 |

Queries on the churned table (pass 1 only, same limits):

| query | main | opt | final | final vs main | final vs opt |
|---|---|---|---|---|---|
| keys_strict | 1.246 | 0.638 | 0.621 | -50% | -3% (nd) |
| keys_nonstrict | 3.155 | 0.645 | 0.630 | -80% | -2% (nd) |
| pages | 0.389 | 0.330 | 0.309 | -21% | -6% (nd) |
| insert | 0.408 | 0.331 | 0.346 | -15% (nd) | +5% (nd) |
| upd_complete | 0.378 | 0.440 | 0.463 | +22% (nd) | +5% (nd) |
| upd_retry | 0.226 | 0.151 | 0.163 | -28% (nd) | +8% (nd) |
| upd_fail | 0.247 | 0.316 | 0.306 | +24% (nd) | -3% (nd) |
| delete_by_id | 0.705 | 0.655 | 0.630 | -11% (nd) | -4% (nd) |
| count_statuses | 3.113 | 4.046 | 4.055 | +30% | +0% (nd) |
| count_partitions | 1.508 | 0.465 | 0.456 | -70% | -2% (nd) |
| oldest_created | 1.507 | 0.537 | 0.285 | -81% | -47% |
| oldest_due_strict | 3.809 | 0.410 | 0.388 | -90% | -5% (nd) |
| oldest_due_nonstrict | 1.032 | 0.636 | 0.408 | -60% | -36% |
| count_new | 0.186 | 0.212 | 0.214 | +15% (nd) | +1% (nd) |
| count_failed | 2.927 | 3.104 | 3.112 | +6% (nd) | +0% (nd) |
| count_completed | 123.3 | 86.9 | 83.4 | -32% | -4% (nd) |
| count_partition_single | 0.102 | 0.226 | 0.082 | -20% (nd) | -64% |

## 6. Equivalence (lane 2, `HARNESS_SAMPLE_ORDERED=1`, before pass 1; `results/final/equiv/mariadb/<scenario>/`)

- `equiv/mariadb/S1/mdb-final-vs-mdb-main.md`: PASS 28
- `equiv/mariadb/S1/mdb-final-vs-mdb-opt.md`: PASS 28
- `equiv/mariadb/S2/mdb-final-vs-mdb-main.md`: PASS 26, SKIP 2
- `equiv/mariadb/S2/mdb-final-vs-mdb-opt.md`: PASS 26, SKIP 2
- `equiv/mariadb/S5/mdb-final-vs-mdb-main.md`: PASS 25, WARN 1, SKIP 2
  - | keys_nonstrict | WARN | limited lists differ (first difference at position 1: a=order-000000000000000000000000000056 b=order-000000000000000000000000000016, 1 key(s) only in a, 1 only in b) but full key sets are equal (100); verified: both lists sorted by MIN(created_at) of the due rows and the cr
- `equiv/mariadb/S5/mdb-final-vs-mdb-opt.md`: PASS 26, SKIP 2
- `equiv/mariadb/S7/mdb-final-vs-mdb-main.md`: PASS 26, SKIP 2
- `equiv/mariadb/S7/mdb-final-vs-mdb-opt.md`: PASS 26, SKIP 2

SKIP rows: the two write queries the harness does not compare on S2/S5/S7 (see the report files). Only WARN: S5 final vs main `keys_nonstrict`, a created_at tie permutation (full key sets equal; both sorted by MIN(created_at); identical created_at sequences). `results/final/failures.txt` lists it.

## 7. Buffer pool 2G sensitivity (one pass; `results/final/bp2g/`)

Container memory stays 4g; `--innodb-buffer-pool-size=2G` via `HARNESS_EXTRA_ARGS`, container recreated (volume and snapshot kept). Verified: `SELECT @@innodb_buffer_pool_size` = 2147483648 (`bp2g/buffer-pool.txt`); the 128M runs show 134217728 in `env.txt`. `mdb-main` then `mdb-final` back to back per cell; limits as above (final: 2x pass-1 `mdb-opt` or 60 s). The container was recreated with the default pool afterwards.

buffer pool: innodb_buffer_pool_size=2147483648 (Sat Nov  7 18:14:46 CET 2026)


### S8-S2 at 2G

| query | main 2G | final 2G | final vs main (2G) | main 128M | final 128M | final vs main (128M) |
|---|---|---|---|---|---|---|
| keys_strict | 0.607 | 0.457 | -25% | 0.595 | 0.468 | -21% |
| keys_nonstrict | 0.961 | 0.494 | -49% | 0.989 | 0.502 | -49% |
| pages | 0.388 | 0.357 | -8% (nd) | 0.387 | 0.334 | -14% |
| insert | 0.270 | 0.246 | -9% (nd) | 0.600 | 0.424 | -29% (nd) |
| upd_complete | 0.253 | 0.258 | +2% (nd) | 0.454 | 0.357 | -21% (nd) |
| upd_retry | 0.185 | 0.168 | -9% (nd) | 0.284 | 0.266 | -7% (nd) |
| upd_fail | 0.191 | 0.195 | +2% (nd) | 0.290 | 0.248 | -14% (nd) |
| delete_by_id | 0.092 | 0.092 | +0% (nd) | 0.400 | 0.348 | -13% (nd) |
| count_statuses | 1.834 | 2.503 | +36% | 1.874 | 2.505 | +34% |
| count_partitions | 0.276 | 0.270 | -2% (nd) | 0.291 | 0.286 | -2% (nd) |
| oldest_created | 0.546 | 0.214 | -61% | 0.569 | 0.218 | -62% |
| oldest_due_strict | 1.249 | 0.321 | -74% | 1.260 | 0.324 | -74% |
| oldest_due_nonstrict | 0.335 | 0.320 | -4% (nd) | 0.348 | 0.318 | -9% (nd) |
| count_new | 0.195 | 0.222 | +14% (nd) | 0.194 | 0.211 | +9% (nd) |
| count_failed | 1.639 | 1.863 | +14% | 1.680 | 1.860 | +11% |
| count_completed | 89.9 | 98.3 | +9% (nd) | 92.5 | 98.6 | +7% (nd) |
| count_partition_single | 0.086 | 0.090 | +5% (nd) | 0.099 | 0.098 | -1% (nd) |

### S8-S4 at 2G

| query | main 2G | final 2G | final vs main (2G) | main 128M | final 128M | final vs main (128M) |
|---|---|---|---|---|---|---|
| keys_strict | 0.693 | 0.501 | -28% | 0.709 | 0.498 | -30% |
| keys_nonstrict | 5538.6* | 0.544 | -100% | 21920.8 | 0.538 | -100% |
| pages | 0.328 | 0.321 | -2% (nd) | 0.322 | 0.319 | -1% (nd) |
| insert | 0.598 | 0.398 | -33% (nd) | 0.637 | 0.509 | -20% (nd) |
| upd_complete | 0.817 | 0.410 | -50% (nd) | 0.785 | 0.907 | +16% (nd) |
| upd_retry | 0.390 | 0.311 | -20% (nd) | 0.395 | 0.425 | +8% (nd) |
| upd_fail | 0.322 | 0.300 | -7% (nd) | 0.286 | 0.253 | -12% (nd) |
| delete_by_id | 0.502 | 0.085 | -83% | 0.485 | 0.470 | -3% (nd) |
| count_statuses | 84.7 | 106.8 | +26% | 85.2 | 106.9 | +26% |
| count_partitions | 29.4 | 29.2 | -1% (nd) | 30.0 | 29.3 | -2% (nd) |
| oldest_created | 7026.6* | 0.275 | -100% | 14550.8 | 0.257 | -100% |
| oldest_due_strict | 7838.9* | 0.376 | -100% | 19017.6 | 0.376 | -100% |
| oldest_due_nonstrict | 45.4 | 0.408 | -99% | 45.6 | 0.402 | -99% |
| count_new | 84.7 | 92.8 | +10% (nd) | 85.1 | 93.1 | +9% (nd) |
| count_failed | 0.071 | 0.087 | +23% (nd) | 0.074 | 0.089 | +21% (nd) |
| count_completed | 92.1 | 99.8 | +8% (nd) | 92.1 | 100.4 | +9% (nd) |
| count_partition_single | 0.473 | 0.482 | +2% (nd) | 0.479 | 0.492 | +3% (nd) |

### S8-S5 at 2G

| query | main 2G | final 2G | final vs main (2G) | main 128M | final 128M | final vs main (128M) |
|---|---|---|---|---|---|---|
| keys_strict | 331.1 | 53.1 | -84% | 13253.9 | 54.1 | -100% |
| keys_nonstrict | 67.1 | 0.598 | -99% | 2178.6 | 0.597 | -100% |
| pages | 266.6 | 50.6 | -81% | 4021.2 | 1269.6 | -68% |
| insert | 0.234 | 0.225 | -4% (nd) | 0.507 | 0.424 | -16% (nd) |
| upd_complete | 0.211 | 0.234 | +11% (nd) | 0.582 | 0.364 | -37% (nd) |
| upd_retry | 0.149 | 0.147 | -1% (nd) | 0.283 | 0.195 | -31% (nd) |
| upd_fail | 0.148 | 0.151 | +2% (nd) | 0.249 | 0.202 | -19% (nd) |
| delete_by_id | 0.081 | 0.080 | -1% (nd) | 0.450 | 0.244 | -46% (nd) |
| count_statuses | 17.1 | 21.3 | +25% | 17.4 | 21.6 | +24% |
| count_partitions | 4.248 | 4.240 | -0% (nd) | 4.290 | 4.268 | -1% (nd) |
| oldest_created | 47.1 | 0.217 | -100% | 2320.8 | 0.211 | -100% |
| oldest_due_strict | 118.7 | 0.580 | -100% | 2418.7 | 0.568 | -100% |
| oldest_due_nonstrict | 6.417 | 0.327 | -95% | 6.399 | 0.313 | -95% |
| count_new | 17.0 | 18.5 | +9% (nd) | 17.3 | 19.0 | +10% (nd) |
| count_failed | 0.075 | 0.081 | +8% (nd) | 0.089 | 0.085 | -4% (nd) |
| count_completed | 27.6 | 29.9 | +8% (nd) | 28.1 | 30.7 | +9% (nd) |
| count_partition_single | 0.296 | 0.291 | -2% (nd) | 0.305 | 0.287 | -6% (nd) |

### S2-wc at 2G (redo B/write / pages/write / p50 / p95 / thr)

- mdb-main: ins1t 2439 / 1.32 / 0.57 / 0.65 / 1705 | ins4t 2254 / 1.30 / 0.98 / 1.16 / 3916 | updco 5621 / 3.21 / 0.61 / 0.71 / 53 | updre 2718 / 2.57 / 0.56 / 0.62 / 64 | updfa 4688 / 2.41 / 0.59 / 0.67 / 79 | delby 2838 / 3.12 / 0.57 / 0.65 / 54
- mdb-final: ins1t 2328 / 1.24 / 0.56 / 0.64 / 1735 | ins4t 2392 / 1.28 / 0.98 / 1.12 / 3951 | updco 8594 / 3.99 / 0.60 / 0.71 / 46 | updre 3063 / 1.97 / 0.56 / 0.64 / 79 | updfa 4161 / 2.32 / 0.58 / 0.66 / 79 | delby 5857 / 3.31 / 0.57 / 0.62 / 54

### S10-S2-p4 at 2G

- mdb-main: processed_per_s: 513.0 inserted_per_s: 497.9 keys_query_ms: n=7169 p50=0.51 pages_query_ms: n=5198 p50=0.17 update_ms: n=30904 p50=0.97
- mdb-final: processed_per_s: 512.9 inserted_per_s: 497.5 keys_query_ms: n=7173 p50=0.36 pages_query_ms: n=5060 p50=0.17 update_ms: n=30926 p50=0.87

128M reference (mean of pass 1/2 above): see S10 table, label S10-S2-p4.

### Does each conclusion of the 128M run hold at 2G? (final vs main)

- Partial ownership (S8-S2/S4/S5) `keys_strict`: -25 % / -28 % / -84 % at 2G (128M: -21 % / -30 % / -100 %): **holds** (smaller gap on S8-S5 because main is faster with a warm pool: 331 ms vs 13.3 s).
- `keys_nonstrict`: S8-S2 -49 % (128M -49 %), S8-S4 main 5.5 s (stopped-run value, 128M 21.9 s) vs final 0.54 ms, S8-S5 67 ms vs 0.6 ms: **holds**.
- `oldest_created`, `oldest_due_strict`: S8-S2 -61 % / -74 %, S8-S4 and S8-S5 -100 % (main 7.0 s / 7.8 s on S8-S4; 47 ms / 119 ms on S8-S5): **holds**. `oldest_due_nonstrict` S8-S4 -99 % (45 ms), S8-S5 -95 % (6.4 ms): **holds**; S8-S2 no difference at both.
- S8-S5 `pages`: -81 % at 2G (main 267 ms, final 51 ms) vs -68 % at 128M (4.0 s vs 1.27 s): **holds** (both faster at 2G; final 25x faster than at 128M).
- `count_statuses` loss: +36 % / +26 % / +25 % at 2G (128M +34 % / +26 % / +24 %): **holds** (unchanged by buffer pool).
- Counts (`count_new`, `count_failed`, `count_completed`): +8..+23 % mostly (nd), same as 128M: **holds**. `count_partitions`: no difference at both: **holds**.
- Rolled-back writes (insert, upd_*, delete_by_id): all (nd) at both sizes (absolute times at 2G are 0.08-0.8 ms, lower than at 128M): **holds**.
- S2-wc write cost at 2G: completion redo 8594 vs 5621 B/write (+53 %; 128M +60 %), delete 5857 vs 2838 B (+106 %; 128M +103 %), retry +13 % (128M +15 %), fail -11 % (128M -13 %), insert 1-thread -5 % and 4-thread +6 % (nd): **holds**. Latencies p50/p95 within 3 % of main at 2G (128M the same).
- S10-S2-p4 (500 rec/s throttled): processed 513.0 vs 512.9 rec/s; keys p50 0.51 vs 0.36 ms, pages 0.17 vs 0.17, update 0.97 vs 0.87 ms; 128M: 512.7/512.8 (final keys p50 0.40 vs 0.52 ms): **holds** (no throughput difference, keys query faster).

## 8. Anomalies

- `mdb-main` `oldest_due_nonstrict` on S4 and S4-b100: pass 1 75.3 s / 81.5 s (1 execution each, plan full scan), pass 2 411 ms (20 reps), rerun 76.3 s / 75.2 s. Pass 2 (reverse order, `mdb-main` last in the cell after the container restart) took a different, fast plan on both labels; cause not investigated. At 2G (bp2g) `mdb-main` S8-S4 `oldest_due_nonstrict` was 45 ms and `keys_nonstrict` / `oldest_*` still 5-8 s.
- `mdb-final` S2-wc pass 1 `insert_1thread` throughput 1066/s vs 1438/s in pass 2 (latency p50 equal 0.69 vs 0.66 ms); `upd_complete` throughput 143 vs 101/s between passes (latency equal). Throughput there is bounded by the harness' flush wait, not judged.
- S4-wc `upd_retry_committed` redo: `mdb-main` 3604 / 3659 B/write, `mdb-opt` 14332 / 14219 B, `mdb-final` 14254 / 14315 B (+293 % vs main, +0 % vs opt, p50 0.79 vs 0.67 ms).
- S10-S2-p1-unthr: `mdb-opt` processed 2.3 / 2.4 rec/s (keys query on the branch SQL; history list length 157k / 133k at the end) vs `mdb-final` 402 / 465 and `mdb-main` 359 / 388 rec/s.
- No other outlier. `mdb-main` S9 retention capped at 2700 s (31,000 of 500,000 rows removed), as in phase 3.

## 9. Deferred-test candidates

- Why `mdb-main` `oldest_due_nonstrict` (S4, S4-b100) takes 411 ms in some runs and 75-82 s in others (plan flip after a restart / statistics state).
- S4-wc retry redo +293 % vs main on both branch variants; test with the retry mix of real traffic.
- `count_statuses` single statement is +24..+42 % slower than main's `count_new` + `count_failed` at every full/partial label; `count_partitions` +23 % vs opt on S6/S8-S6.
- S16-S2 `keys_nonstrict` +64 % vs main (0.66 vs 0.40 ms): small absolute value, check with 16-partition ownership on larger data.
- Completion/delete redo (+60 % / +103 % vs main on S2-wc) with a time-based mix (S9 churn at larger scale, retention enabled).
- Buffer pool between 128M and 2G for S4/S5 full ownership (`mdb-opt` collapse and main cases at 4g container memory).
