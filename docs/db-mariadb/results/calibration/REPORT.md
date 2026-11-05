# Calibration report (MariaDB 12.3.3, lanes 1 and 2, 2026-11-05)

Variants: `mdb-main` f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f, `mdb-opt` 2a2663de2f2028790180399293981f7eca587ad1; both worktrees clean
(`git status --short -- . ':!docs'` empty). Groups `HOT,WRITE,MON`; limits: mdb-main 900 s, mdb-opt `--limit-from` (2x, min 60 s) when mdb-main ran first
(pass 1, load A, load B); pass 2 runs mdb-opt first, so both use 900 s. No query hit a limit. No harness code changed.

## Trees
- `pass1/` lane 1 solo (forward: S8-S2, S2-wc, S8-S4, S8-S5; main then opt). Lane 2 idle, container stopped state of lane 2 not running a step.
- `pass2/` after `bin/db.sh stop/start` of lane 1, reverse variant order (opt then main) and reverse scenario order (S8-S5, S8-S4, S2-wc, S8-S2) to save regenerations.
- `loadA/` lane 1 `S8-S2`, `S8-S4` while lane 2 ran `gen S4` and then `S4` bench of mdb-main (looped; lane 2 was busy during all lane-1 steps, 14:29-14:59; lane-2 loop trees `loadA-L2-1`).
- `loadB/` same cells while lane 2 ran `gen S2`, then repeated `S10` concurrency (prepare + `--pollers 4 --seconds 60 --rate 500`, 8 iterations `loadB-L2-1..8`, 15:00-15:31). Lane 2 alternates prepare (insert-select, heavy) and 60 s of concurrency, so load B is a mix of both; lane-1 steps overlapped 3-4 iterations each.
- Comparison files: `noise-pass1-pass2.md` (solo noise, all labels), `noise-pass{1,2}-vs-load{A,B}.md`, `sig-interference.txt` (signed change vs mean of the two solo passes, per group), `writecost-noise.md`, `plan-flips.txt`, `checksums.txt`.

## 1. Noise (pass 1 vs pass 2; medians; relative spread = |m1-m2|/min for queries >= 0.3 ms)

| group | n | p90 rel spread | max rel spread | worst |
|---|---|---|---|---|
| HOT | 18 | 8% | 11% | S8-S4 mdb-main keys_nonstrict 27.6 vs 24.8 s |
| MON | 49 | 4% | 10% | S8-S2 mdb-opt count_partitions 0.47 vs 0.52 ms |
| WRITE | 24 | 30% | 60% | S8-S4 upd_retry 0.59 vs 0.37 ms (sub-ms) |

Per variant: main HOT 8/11%, MON 2/8%, WRITE 31/42%; opt HOT 7/9%, MON 4/10%, WRITE 22/60% (p90/max).
Queries < 0.3 ms, absolute |m1-m2| (p90 / max): WRITE 0.133 / 0.168 ms (6 queries), MON 0.026 / 0.039 ms (5).
Proposed thresholds: HOT and MON differences below ~10% (p90) / 15% (max) are noise; WRITE (rolled-back single-row writes, 0.3-0.7 ms) needs
>= 0.2 ms absolute and >= 60% relative to be called a difference, or repeat with more reps.

### Write cost (S2-wc, committed writes; `writecost-noise.md`)
Max pass1-vs-pass2 relative difference over both variants and all 6 phases: latency p50 8%, latency p95 25%, redo bytes/write 12% (typically 1-4%),
pages/write 64% (typical 1-10%; outlier: mdb-main upd_complete 4.41 vs 2.80, page flushing timing even after the forced flush). Redo bytes/write is the stable metric (use a 15% threshold);
pages/write needs a 30%+ margin or a repeat.

## 2. Lane interference (lane 1 cells S8-S2, S8-S4 while lane 2 loaded; baseline = mean of pass1/pass2 medians)

Per-group spread against each solo pass (`noise-pass*-vs-load*.md`, all four cells together), p90 / max:

| load | HOT | WRITE | MON |
|---|---|---|---|
| solo noise (pass1 vs pass2, same 2 labels) | 9% / 11% | 40% / 60% | 5% / 10% |
| A: gen S4 + S4 bench (main) | 9-14% / 17-18% | 28-38% / 148-170% | 69-72% / 124-131% |
| B: S10 concurrency loop | 43-45% / 84-104% | 194-223% / 258-319% | 100-102% / 174-179% |

Details (`sig-interference.txt`): the short queries (pages, count_*, sub-ms/ms MON) stay within a few percent under A (e.g. mdb-main S8-S2 count_failed +1%); the damage is concentrated in
(i) long scans of mdb-main on S4 (oldest_created 14.6 -> 32.5 s, +122%; oldest_due_strict 22.2 -> 61.3 s under B, +177%; keys_nonstrict 26.2 -> 50.8 s under B, +94%) and (ii) WRITE
queries with rollback (delete_by_id 0.45 -> 1.16 ms under A/B, 0.57 -> 2.19 ms mdb-opt S8-S4 under B, i.e. +130% to +290%). mdb-opt's short HOT queries moved -9% to +1% (no visible interference).
Load B also shows queries getting *faster* than solo (MON median -34 to -49%, e.g. mdb-opt S8-S2 oldest_due_nonstrict 0.87 -> 0.74): host load fell from ~12 to ~5 during the second half of load B (see
anomalies), so B mixes lane interference with an external-load change and is not a clean estimate; it is still far outside the solo noise.

## 3. Data identity
`checksums.txt` (CHECKSUM TABLE snap.rec): S2 lane 1 = lane 2 = 3370317100 (pass1, pass2, loadB; lane 2 in loadB);
S4 lane 1 = lane 2 = 593475532 (pass1, pass2, loadA lane 1+2, loadB lane 1); S5 lane 1 = 3839299731 (generated once; no second lane-1 gen, pass 2 reused it). Equal where generated in both lanes: yes.

## 4. Recommendation: (b)
Timings of different lanes must not overlap: interference exceeds the solo noise by 10x in the worst cases (long scans +120-180%, rolled-back writes up to +290%) even though the lanes have separate containers, ports and pinned CPU sets
(shared host resources: memory bandwidth/L3 across CCD boundaries is not the issue here; the mdb-main long scans and the write path are sensitive to storage/host load). Parallel lanes are acceptable for `gen`, `prepare` and equivalence while the other lane is not timing. Practical use of the second lane: data generation, equivalence runs, plan captures and dry checks; every timed cell (bench, writecost, concurrency, churn) serial in lane 1 with lane 2 idle.

## Anomalies
- **Host load.** `/proc/loadavg` was 11-13 at nearly every step start (rule: > 4 -> rerun), including with both containers idle (`top` showed 31% user CPU with no local process using it; `vmstat` r=11): the load comes from outside this LXC (other tenants on the host),
  not from the harness. A rerun cannot satisfy the rule, so no cell was rerun for that reason; steps with loadavg <= 4 were not available except load-B tail (4.59-5.7 at 15:12-15:22). All timings are therefore measured on a noisy host, and the solo noise figures above include that external noise
  (pass 1 start load 6.3-13, pass 2 11.3-13.0). Recommend measuring the final matrix with `loadavg` recorded and using the p90 thresholds above.
- **Plan flips.** mdb-opt S8-S2, `count_new`, `count_partitions`, `count_partition_single`: index `idx_outbox_record_status_completed_partition` (pass 1, load A) vs `idx_outbox_record_status_completed_retry` (pass 2, load B) - equal-cost covering indexes chosen by sampled persistent statistics after each `prepare`
  (`innodb_stats_persistent_sample_pages=20`). Timings differ by <= 0.05 ms (0.368 vs 0.384 ms in pass 1/2); load B's 0.22 ms is the external-load effect. No other flips (mdb-main none; S8-S4, S8-S5 none).
- **Outliers.** mdb-opt S8-S2 `count_partition_single` p95/max 1.05/1.56 ms (pass 2), max 2.36 ms (load A) with a median unchanged: single-slow-rep spikes, medians fine. mdb-opt S8-S4 oldest_due_nonstrict 278 vs 159 ms (pass1 vs loadA).
- mdb-main S4 long queries (15-60 s, 1 warm-up + 5 reps) make the S8-S4 main cell ~15 min per pass; their relative noise solo is 8-11%.
- Lane-2 iteration 2 of load A (and iteration 9 of load B) were stopped after lane 1 finished (processes and the running server query killed; partial result trees removed). Pass 2 skipped the S5 regeneration (already loaded in lane 1), so S5 has one checksum.
- Phase B runs with the lane-2 prepare steps, so the load alternates between insert-select and concurrency, per above.
