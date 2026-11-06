# Calibration report (MySQL, lane 1, S8-S2 / S8-S4 / S8-S5 / S2-wc)

Variants: `my-main`, `my-opt` (HEAD 2a2663de2f2028790180399293981f7eca587ad1). Pass 1 `cal-p1` (my-main, my-opt), container restart, pass 2 `cal-p2` (reverse order), background run `cal-bg`. Spread = |m1-m2| / min(m1,m2) of per-query medians; statistics over queries with min >= 0.3 ms. Full tables: `noise-p1-p2.md`, `noise-p1-bg.md`, `noise-p2-bg.md`.

## Noise thresholds, pass 1 vs pass 2 (all four labels, both variants, "all" rows)

| group | n | threshold (p90 spread) | max spread | outliers |
|---|---|---|---|---|
| HOT | 18 | **9%** (relative) | 15% | S8-S4/pages my-opt 15%, S8-S5/keys_strict my-main 14% (47.5 vs 54.3 s) |
| MON (>= 0.3 ms) | 50 | **8%** relative | 151% | S8-S2/count_new my-main 1.35 vs 0.54 ms (151%); count_partition_single my-opt 31% (0.78 vs 0.59 ms); sub-3 ms MON queries are the noisy ones, large scans (>100 ms) are within 0-8% |
| MON (< 0.3 ms) | 4 | **0.065 ms** (absolute, p90) | 0.066 ms | S8-S2/S8-S4 count_partition_single / count_failed |
| WRITE (single-row, 1-3 ms) | 30 | **104%** relative (not usable as a threshold) | 156% | S8-S2/upd_fail my-main (2.41 vs 0.94 ms), S8-S5/upd_retry my-opt 136%, delete_by_id 103% |

Reading: HOT and large MON queries can be compared with a ~10% threshold. The single-row WRITE statements (1-3 ms, one transaction each, commit/fsync dominated) swing by up to 2.5x between passes; their timing comparison is not decisive. Judge write cost by the write-cost counters below, not by the bench WRITE timings.

## Write-cost noise (S2-wc, pass 1 vs pass 2, per write; 5000 inserts, 333 updates/deletes per phase)

| metric | my-main max / typical | my-opt max / typical |
|---|---|---|
| redo bytes per write (os_log_written) | 8.7% (delete_by_id) / 1-2% | 5.3% (upd_fail) / 0.1-2.8% |
| redo LSN per write | 3.4% | 5.6% (upd_retry) |
| pages written per write | 10.4% (upd_retry), 7.0% delete / 1-4% insert | 12.8% (upd_retry), 12.6% (upd_complete), 8.8% (insert 4 threads) |
| data written per write | 9.5% | 12.2% |
| latency p50 / p95 | p50 up to 27% (delete_by_id), upd_fail 20%; others <= 6%. p95 up to 18% | p50 <= 3.1%; p95 <= 5.1% (insert warmup p95 17%) |
| throughput | <= 10.6% (delete_by_id) | 12.9% (insert 4 threads) |
| binlog bytes, index/table sizes after | 0.0-1.4% (binlog identical) | 0.0% |

Suggested thresholds: redo bytes/write 9%, pages (and data) written per write 13%, p50 latency 10% for inserts/updates (my-main delete_by_id showed 27%). No row-lock waits or deadlocks in any phase. Per-write byte counts are deterministic enough that differences between variants above these values are real.

## Background sensitivity (cal-bg)

S8-S2 bench cells for both variants (forward order) while a Gradle build loop (`taskset -c 0,4,5 ./gradlew --offline --no-configuration-cache --rerun-tasks build -x test -x ktlintCheck`, 3 min per iteration, 7 iterations, ran for the whole timed part, 17:26-17:47) ran in the my-opt worktree. The driver ran with LOAD_MAX=100 (load gate off; loadavg was 4-6 because of the build). First attempt (started with `build` incl. jacoco, failed offline in 1 s) was aborted and the results directory deleted; the reported run is the second attempt.

| comparison (S8-S2 only) | HOT p90 / max | MON p90 / max | WRITE p90 / max |
|---|---|---|---|
| p1 vs p2 (baseline) | 7% / 7% | 22% / 151% | 108% / 156% |
| p1 vs bg | 5% / 5% | 52% / 118% | 94% / 106% |
| p2 vs bg | 6% / 7% | 40% / 56% | 79% / 94% |

Verdict: HOT queries (the ones that run tens of ms to seconds) moved by <= 7%, the same as the pass-to-pass noise (<= 7%); MON/WRITE spreads are in the same range as the baseline spread (sub-3 ms queries, baseline already 22-151%). No effect above the noise threshold is visible when the load runs on the free cores 0,4,5. The rule "no Gradle during timed steps" is therefore not proven mandatory for a build pinned to the free CPUs; it stays advisable as a precaution for sub-millisecond/small queries and for unpinned builds (this test did not cover unpinned load, memory pressure or disk I/O heavy work; the container page cache and the build's disk I/O were shared).

## Host load at step starts (1/5/15-min loadavg)

- cal-p1: 1.96, 2.92, 2.89, 2.81, 1.52, 1.02, 1.63, 1.05 (one 10 s wait: 3.41 > 3).
- cal-p2: 1.19, 1.07, 1.65, 1.98, 1.06, 1.46, 2.40, 2.73, 2.61.
- cal-bg: 4.37, 5.85 (gate disabled, build running).
Steps ran at 1.0-2.9 (the 1-min figure reflects the previous step's tail, e.g. the snapshot copy).

## Anomalies

- cal-p2 my-opt S8-S4 was interrupted by a session crash and rerun (two RUN lines in the log); the final timings.csv is from the complete rerun.
- One snapshot copy took 1289 s (rest of the copies were minutes).
- WRITE bench timings of single-row statements are too noisy for conclusions (see above).
- S8-S2 MON count_new my-main: 1.35 ms in pass 1 vs 0.54 / 0.62 ms in pass 2 / bg, so pass 1 was the outlier.
