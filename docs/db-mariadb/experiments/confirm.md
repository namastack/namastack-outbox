# Confirm stage - `c-1` and `c-2` against the branch (`c-ref`) and `mdb-main`

MariaDB 12.3.3 (`mariadb:12`), lane 1 only (lane 2 idle during every timed step; lane 2 ran only the equivalence checks, before pass 1). Stage `confirm` + `validate` in one run: all labels, two passes in reversed order with a container stop/start between them, solo cells as in phase 3 (`03-main-vs-branch.md`). Spec `experiments/confirm-spec.md`, brief `briefs/06-experiment.md`. Raw data `results/c/{pass1,pass2,equiv,iso}/`, logs `results/c/logs/` (`driver.log` has one line per cell with load average and free disk; `pass*/logs/*-L1.log` has the harness output). `results/c/failures.txt` does not exist: no failed step, no `FAILED`/`ABORT` line in any log, nothing rerun.

## 0. Run record

- **Worktree** `mdb-opt` `/home/azamojski/outbox-wt-mdb-opt` at `2a2663de2f2028790180399293981f7eca587ad1`, `git status --short -- . ':!docs'` empty before and after; never modified. `git_dirty_files` is empty in every `env.txt`.
- **Ids: UUIDv4 only.** `HARNESS_ID_VERSION` was never set (the drivers `unset` it). The round-2 v7 snapshot markers were deleted before the first `gen`; no marker had an `ids=uuidv7` line at any step (the drivers check before every cell and would abort); `ids=v4` is written to `results/c/{pass1,pass2,iso}/<run>/ids.txt` and `results/c/equiv-ids.txt`; an id of the final snapshot is `5c1da71a-45c4-49f8-a4b4-...` (version nibble 4).
- **Runs** (all `--catalog-variant mdb-opt`; image id 2215e9af9663):
  - `c-ref`: worktree DDL and SQL. Four indexes: i1 `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, i2 `(record_key, completed_at, created_at, id)`, i3 `(status, completed_at, next_retry_at, partition_no)` and `idx_outbox_record_status_completed_partition (status, completed_at, partition_no)`.
  - `c-1`: `experiments/r2/spc/ddl.sql` (i1, i2, i3 + `idx_outbox_record_status_partition_created (status, partition_no, created_at)`) and the `forms-a` overlays `ks-05-head`, `kn-05-head`, `oc-03-order-id`, `odn-01-order` (`experiments/r2/forms-a/queries/`).
  - `c-2`: `experiments/c/c-2/ddl.sql` (= `experiments/r1b/b-e5l/ddl.sql` + `idx_outbox_record_status_partition_created (status, partition_no, created_at)`; indexes `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2, `(status, next_retry_at, partition_no)` and the 4th) and the `forms-e5h` overlays (`ks-11`, `kn-14`, `oc-08`, `odn-06`, `ods-09`; they rely on NEW <=> `completed_at IS NULL`).
  - `mdb-main` was not re-run; its values are from `results/cmp/` (same harness, v4): the mean of pass 2 and the rerun (`rerun1/`, else pass 1) of phase 3; S9 pass 1 only. The `main (ph3)` / `opt (ph3)` columns are these means; main `count_statuses` = `count_new` + `count_failed`.
- **Drivers** `experiments/c/run-c.sh pass1|pass2|iso`, `experiments/c/run-equiv.sh`; summarizers `experiments/c/summarize.py`, `plans.py`. Pass 1 20:07-01:20 (the S9 churn of `c-2` took 49 min, section 5), container stop/start, pass 2 01:20-05:28, isolation test 05:28-06:37 (2026-11-06/07). Pass 1 order `c-ref, c-1, c-2` with S1, S2, S8-S2, S16-S2, S2-wc, S9, S10-S2 x4, S4, S8-S4, S16-S4, S4-b100, S4-wc, S10-S4 x2, S5, S8-S5, S6, S8-S6, S7, S8-S7; pass 2 `c-2, c-1, c-ref` with the same list reversed and no S9 (pass 1 only, as in phase 3). Bench cells `--groups HOT,WRITE,MON`, 5 warm-up + 20 reps, `--prepare` per run.
- **Limits.** `c-ref`: 10 s on S4, S4-b100, S5, S6, S7, 60 s elsewhere. `c-1`/`c-2`: `--query-limit-s 60 --limit-from <pass-1 c-ref timings> --limit-factor 2 --limit-min-s 60`; no `c-1`/`c-2` query was stopped in any cell. `pages` keys: `experiments/r1/s4-pages-keys.txt` / `s4-pages-keys-b100.txt` on S4 / S4-b100 for all runs, elsewhere each run's own `keys_strict` result. Consequence: `c-ref` S5 `pages` is not measured (its `keys_strict` is stopped at 10 s, so `pages` errors); phase 3 `mdb-opt` S5 `pages` was 1.5 s, the same as `c-1`/`c-2` now.
- **Host load.** `/proc/loadavg` is logged at every cell start in `logs/driver.log`: 0.9-4.0 for all cells but one; maximum 16.8 (pass 2, `c-1` S4, 04:03; 5.8 and 3.0 in the 5 and 15-minute averages). The rerun rule (load > 18) never triggered. **No cell has a pass-1 vs pass-2 spread above 2x the noise threshold** (no `SPREAD>2x` flag below), so there was no spread rerun.
- **Host speed.** The host was faster than during phase 3 on the small labels (S1, S2, S8-S2, S16-S2, S9: `c-ref` is 1.5-2x faster than `mdb-opt` of phase 3, which ran at outside load 11-15) and equal on S4-S7. A raw `vs main` change on those labels mixes in this effect, so the tables also give `vs main (scaled)` = candidate / (main x `c-ref` / `opt (ph3)`), using the phase-3 `mdb-opt` as the speed reference. Use the scaled column on S1, S2, S8-S2, S16-S2, S9.
- Noise thresholds (STATE.md): HOT 11 %, MON 10 % (0.04 ms absolute below 0.3 ms), WRITE (rolled-back statements) 60 % and >= 0.2 ms, writecost redo 12 %, latency p50 8 % / p95 25 %, pages/write 64 % (supporting only). `(nd)` = no difference (below the threshold).

## 1. Verdict

**Equivalence: both PASS** (section 7), so both are valid candidates. Verdicts use the ADR-0005 rule (P1/P2 justify indexes, read benefit against write cost, P3 reported only); partial ownership ranks first.

### `c-1` (`spc` index + `forms-a`)

- **Against `c-ref`: win.**
  - The branch collapses at full ownership on S4, S4-b100, S5, S6, S7: `keys_*` and `oldest_*` take 0.8-17 s (stopped at 10 s on S4, S4-b100, S5 and for most queries on S6). `c-1`: `keys_strict` 0.67 ms (S4), 0.63 ms (S5), 0.85 ms (S6); `oldest_*` 0.3-0.7 ms everywhere.
  - Partial ownership: S8-S4 `oldest_created` 163 -> 0.25 ms, `oldest_due_nonstrict` 142 -> 0.38 ms, `count_partitions` 115 -> 29 ms. S16-S4 `oldest_created` 5025 -> 0.18 ms, `oldest_due_nonstrict` 5036 -> 0.21 ms, `count_partitions` 93-107 -> 7.3 ms. S8-S5 `keys_strict` 109 -> 54 ms, `keys_nonstrict` 4.9 -> 0.59 ms, `oldest_created` 32 -> 0.21 ms, `oldest_due_nonstrict` 29 -> 0.34 ms, `count_partitions` 19.7 -> 4.3 ms. S8-S6 `count_partitions` 19.7 -> 6.3 ms, S8-S7 `oldest_*` 1.9 -> 0.2-0.3 ms.
  - S10-S2-p1-unthr: 2.0 / 2.2 rec/s -> 422 / 439 rec/s (the `c-ref` collapse is the `keys_*` query). Other S10 cells: no difference.
  - S9: `oldest_created` -42 %, `oldest_due_nonstrict` -34 %, the rest no difference.
  - Price: S2-wc completion redo 4652/4596 -> 8708/8461 B per write (+87 % / +84 %), delete 2107/2080 -> 5784/5700 B (2.7x); insert, retry, fail no difference. S4-wc: the `c-ref` insert penalty is gone (1-thread redo 5713/5697 -> 2642/2641 B, -54 %), completion, retry, fail no difference, delete +14 % (1828/1837 -> 2088/2100 B). Total index bytes after S4-wc 725 -> 686 MB (-5 %).
  - Remaining losses against `c-ref`: `count_partitions` at full ownership +21-25 % (S5 22 -> 27 ms, S6 22 -> 27 ms, S7 1.4 -> 1.7 ms; a scan of a wider index, section 6) and `count_statuses` +9-10 % on S6 / S8-S6 (S8-S6 below threshold).
- **Against `mdb-main`: mixed (net win).**
  - Wins where main collapses: S4 `keys_nonstrict` 91 s -> 0.66 ms, `oldest_*` 20-85 s -> 0.34-0.43 ms; S5 `keys_nonstrict` 13.9 s -> 0.70 ms, `count_partitions` 13.2 s -> 27 ms, `oldest_created` 3.0 s -> 0.28 ms; S6 `keys_*` 0.4 s -> 0.85 ms; S7 `keys_nonstrict` 752 ms -> 0.61 ms; S8-S5 `keys_strict` 15.1 s -> 54 ms, `oldest_created` 2.4 s -> 0.21 ms. S10-S2-p1-unthr 342 / 394 -> 422 / 439 rec/s, S10-S2-p4-unthr 969 / 981 -> 1323 / 1324, S10-S4-p4 701 / 768 -> 961 / 903, S10-S4-p4-unthr 370 / 388 -> 535 / 564.
  - Losses (also present in `c-ref`, not caused by the candidates): `count_statuses` +20-45 % against main's `count_new` + `count_failed` (S2 2.5 vs 1.8 ms, S4 107 vs 85 ms, scaled +13..+43 %); S16-S2 `keys_nonstrict` 0.39 -> 0.68 ms (+66-73 %); S16-S2 `oldest_due_strict` +14-19 %.
  - Write cost against main: S2-wc completion 8.6k vs 5.6k B (+54 %), delete 5.7k vs 2.9k B (2x); S4-wc insert and completion equal main, delete +26 %; retry, fail, insert equal.

### `c-2` (E5 indexes + `forms-e5h`)

- **Against `c-ref`: win**, same read results as `c-1` within noise, except the items in section 8 (`oldest_created` about +0.1 ms against `c-1`; `count_statuses` +6-19 % and full-ownership `count_partitions` +14-40 % against `c-ref`). Write cost equal to `c-1` within noise (S2-wc completion 9665/8823 B, +108 % / +92 % against `c-ref`; delete 6021/6195 B; S4-wc insert 2546/2632 B).
- **Against `mdb-main`: mixed (net win)**, for the same reasons as `c-1`. Additionally the retention path (P3, not judged) degrades: no `completed_at` index, so `ids_completed_before` sorts all COMPLETED rows on every batch (S9: retention capped at 2700 s with 26,000 of 500,000 rows removed, section 5), as in main.

### Recommendation `c-1` vs `c-2`

P1/P2 reads, S10 and write cost are **no difference within noise**. Measurable differences (section 8): `c-2` index bytes 2-3 % lower (S2 336 vs 347 MB at load, S4 630 vs 642 MB); `c-2` `oldest_created` +0.10 ms on partial ownership (0.31 vs 0.21 ms S8-S2, 0.38 vs 0.25 ms S8-S4, 0.33 vs 0.21 ms S8-S5, 0.34 vs 0.22 ms S8-S6); `c-2` `count_statuses` +6-19 % (S2, S8-S6, S6) and full-ownership `count_partitions` +14-40 % against `c-ref` (`c-1` +21-25 %); `c-2` has no retention/administration index. **Recommend `c-1`**: it does not depend on the NEW <=> `completed_at IS NULL` invariant, keeps a `completed_at` index for retention, and costs 2-3 % more index bytes. If only P1/P2 count, the two are equal and `c-2` is the smaller schema.

### Isolation test (read penalty of the 4th index): **planning, not execution** (section 9).

## 2. Reads: query x label, partial ownership first

Medians in ms (pass 1 / pass 2 columns). `*` = very slow query (1 execution, harness rule). `over N s` = stopped by the per-query limit. The change against `c-ref` uses the mean of both passes; `ref over limit` means `c-ref` was stopped and the candidate answered in the ms given. `vs main` = raw mean of the phase-3 values, `scaled` see section 0. Labels in order: S8-S2, S8-S4, S8-S5, S8-S6, S8-S7, S16-S2, S16-S4, then S1, S2, S4, S4-b100, S5, S6, S7, S9 (pass 1 only). Writes (insert, upd_*, delete_by_id) are rolled-back single statements (60 % threshold); the committed write cost is in section 3.


### S8-S2

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.484 | 0.470 | 0.471 | 0.470 | 0.497 | 0.467 | 0.962 | 0.777 | -1% (nd) | +1% (nd) | -51% | -50% | -20% | -18% |  |
| keys_nonstrict | 0.504 | 0.489 | 0.493 | 0.503 | 0.498 | 0.497 | 1.648 | 0.788 | +0% (nd) | +0% (nd) | -70% | -70% | -52% | -52% |  |
| pages | 0.327 | 0.316 | 0.327 | 0.322 | 0.318 | 0.323 | 0.639 | 0.502 | +1% (nd) | -0% (nd) | -49% | -50% | -21% | -22% |  |
| insert | 0.456 | 0.465 | 0.441 | 0.453 | 0.419 | 0.409 | 0.685 | 0.534 | -3% (nd) | -10% (nd) | -35% (nd) | -40% (nd) | -24% (nd) | -30% (nd) |  |
| upd_complete | 0.327 | 0.351 | 0.382 | 0.411 | 0.382 | 0.363 | 0.533 | 0.422 | +17% (nd) | +10% (nd) | -26% (nd) | -30% (nd) | -7% (nd) | -13% (nd) |  |
| upd_retry | 0.257 | 0.256 | 0.251 | 0.251 | 0.256 | 0.264 | 0.308 | 0.294 | -2% (nd) | +1% (nd) | -19% (nd) | -16% (nd) | -7% (nd) | -3% (nd) |  |
| upd_fail | 0.282 | 0.193 | 0.192 | 0.194 | 0.245 | 0.261 | 0.291 | 0.331 | -19% (nd) | +7% (nd) | -34% (nd) | -13% (nd) | -8% (nd) | +21% (nd) |  |
| delete_by_id | 0.338 | 0.326 | 0.348 | 0.323 | 0.331 | 0.326 | 0.450 | 0.404 | +1% (nd) | -1% (nd) | -25% (nd) | -27% (nd) | -9% (nd) | -11% (nd) |  |
| count_statuses | 2.352 | 2.371 | 2.499 | 2.509 | 2.654 | 2.670 | 3.615 | 4.740 | +6% (nd) | +13% | -31% | -26% | +39% | +48% |  |
| count_partitions | 0.273 | 0.301 | 0.291 | 0.276 | 0.289 | 0.289 | 0.497 | 0.497 | -1% (nd) | +1% (nd) | -43% | -42% | -1% (nd) | +1% (nd) |  |
| oldest_created | 0.414 | 0.416 | 0.215 | 0.212 | 0.309 | 0.297 | 0.962 | 0.695 | -49% | -27% | -78% | -69% | -63% | -47% |  |
| oldest_due_strict | 0.324 | 0.325 | 0.328 | 0.316 | 0.322 | 0.318 | 2.189 | 0.506 | -1% (nd) | -1% (nd) | -85% | -85% | -77% | -77% |  |
| oldest_due_nonstrict | 0.474 | 0.478 | 0.326 | 0.303 | 0.319 | 0.310 | 0.591 | 0.867 | -34% | -34% | -47% | -47% | -3% (nd) | -3% (nd) |  |
| count_new | 0.206 | 0.209 | 0.224 | 0.219 | 0.207 | 0.208 | 0.346 | 0.376 | +7% (nd) | +0% (nd) | -36% | -40% | +16% (nd) | +9% (nd) |  |
| count_failed | 1.738 | 1.832 | 1.872 | 1.885 | 1.983 | 1.988 | 3.268 | 3.479 | +5% (nd) | +11% | -43% | -39% | +12% | +18% |  |
| count_completed | 98.8 | 102.7 | 98.5 | 98.8 | 98.3 | 97.7 | 187.5 | 203.8 | -2% (nd) | -3% (nd) | -47% | -48% | +6% (nd) | +6% (nd) |  |
| count_partition_single | 0.207 | 0.221 | 0.104 | 0.097 | 0.097 | 0.080 | 0.136 | 0.361 | -53% | -59% | -26% (nd) | -35% | +25% (nd) | +10% (nd) |  |

### S8-S4

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.539 | 0.524 | 0.499 | 0.509 | 0.484 | 0.480 | 1.146 | 0.877 | -5% (nd) | -9% (nd) | -56% | -58% | -27% | -31% |  |
| keys_nonstrict | 0.566 | 0.571 | 0.552 | 0.536 | 0.542 | 0.534 | 26187.5 | 0.964 | -4% (nd) | -5% (nd) | -100% | -100% | -100% | -100% |  |
| pages | 0.347 | 0.327 | 0.334 | 0.321 | 0.335 | 0.350 | 0.538 | 0.535 | -3% (nd) | +2% (nd) | -39% | -36% | -3% (nd) | +1% (nd) |  |
| insert | 0.532 | 0.498 | 0.493 | 0.483 | 0.491 | 0.506 | 0.806 | 0.603 | -5% (nd) | -3% (nd) | -39% (nd) | -38% (nd) | -29% (nd) | -28% (nd) |  |
| upd_complete | 0.777 | 0.776 | 0.906 | 0.980 | 0.878 | 0.946 | 0.915 | 0.914 | +21% (nd) | +17% (nd) | +3% (nd) | -0% (nd) | +21% (nd) | +17% (nd) |  |
| upd_retry | 0.423 | 0.466 | 0.401 | 0.431 | 0.425 | 0.407 | 0.414 | 0.477 | -6% (nd) | -6% (nd) | +0% (nd) | +0% (nd) | +8% (nd) | +8% (nd) |  |
| upd_fail | 0.189 | 0.340 | 0.319 | 0.269 | 0.163 | 0.159 | 0.269 | 0.333 | +11% (nd) | -39% (nd) | +9% (nd) | -40% (nd) | +38% (nd) | -25% (nd) |  |
| delete_by_id | 0.479 | 0.558 | 0.504 | 0.439 | 0.463 | 0.403 | 0.548 | 0.568 | -9% (nd) | -16% (nd) | -14% (nd) | -21% (nd) | -6% (nd) | -14% (nd) |  |
| count_statuses | 117.5 | 117.2 | 106.6 | 106.9 | 106.7 | 106.9 | 176.3 | 219.8 | -9% (nd) | -9% (nd) | -39% | -39% | +13% | +13% |  |
| count_partitions | 115.3 | 114.4 | 29.2 | 29.5 | 29.6 | 29.8 | 61.7 | 197.3 | -74% | -74% | -52% | -52% | -18% | -17% |  |
| oldest_created | 163.0 | 162.2 | 0.246 | 0.243 | 0.379 | 0.392 | 14633.0 | 297.1 | -100% | -100% | -100% | -100% | -100% | -100% |  |
| oldest_due_strict | 0.386 | 0.387 | 0.354 | 0.355 | 0.362 | 0.367 | 22173.0 | 0.596 | -8% (nd) | -6% (nd) | -100% | -100% | -100% | -100% |  |
| oldest_due_nonstrict | 142.4 | 141.4 | 0.384 | 0.385 | 0.397 | 0.405 | 90.7 | 279.8 | -100% | -100% | -100% | -100% | -99% | -99% |  |
| count_new | 102.6 | 102.7 | 92.7 | 92.8 | 93.0 | 93.2 | 176.2 | 190.4 | -10% (nd) | -9% (nd) | -47% | -47% | -2% (nd) | -2% (nd) |  |
| count_failed | 0.082 | 0.090 | 0.079 | 0.093 | 0.080 | 0.078 | 0.137 | 0.126 | +0% (nd) | -8% (nd) | -37% | -42% | -8% (nd) | -15% (nd) |  |
| count_completed | 100.9 | 101.8 | 100.4 | 101.4 | 100.1 | 101.3 | 189.1 | 205.7 | -0% (nd) | -1% (nd) | -47% | -47% | +8% (nd) | +8% (nd) |  |
| count_partition_single | 96.1 | 96.2 | 0.486 | 0.465 | 0.484 | 0.473 | 0.929 | 173.9 | -100% | -100% | -49% | -48% | -7% (nd) | -7% (nd) |  |

### S8-S5

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 108.6 | 106.1 | 53.8 | 53.9 | 49.5 | 51.1 | 15134.3 | 196.6 | -50% | -53% | -100% | -100% | -99% | -99% |  |
| keys_nonstrict | 4.917 | 4.901 | 0.590 | 0.589 | 0.595 | 0.570 | 2435.9 | 8.935 | -88% | -88% | -100% | -100% | -100% | -100% |  |
| pages | 1249.1 | 1328.8 | 1254.7 | 1265.7 | 1326.9 | 1288.9 | 4944.9 | 1475.3 | -2% (nd) | +1% (nd) | -75% | -74% | -71% | -70% |  |
| insert | 0.453 | 0.445 | 0.452 | 0.453 | 0.446 | 0.358 | 0.673 | 0.571 | +1% (nd) | -10% (nd) | -33% (nd) | -40% (nd) | -14% (nd) | -24% (nd) |  |
| upd_complete | 0.391 | 0.423 | 0.426 | 0.399 | 0.428 | 0.342 | 0.701 | 0.500 | +1% (nd) | -5% (nd) | -41% (nd) | -45% (nd) | -28% (nd) | -33% (nd) |  |
| upd_retry | 0.255 | 0.260 | 0.257 | 0.234 | 0.236 | 0.164 | 0.348 | 0.324 | -5% (nd) | -22% (nd) | -30% (nd) | -43% (nd) | -11% (nd) | -28% (nd) |  |
| upd_fail | 0.262 | 0.250 | 0.264 | 0.273 | 0.242 | 0.161 | 0.324 | 0.328 | +5% (nd) | -21% (nd) | -17% (nd) | -38% (nd) | +6% (nd) | -20% (nd) |  |
| delete_by_id | 0.302 | 0.312 | 0.255 | 0.245 | 0.262 | 0.249 | 0.476 | 0.374 | -19% (nd) | -17% (nd) | -47% (nd) | -46% (nd) | -36% (nd) | -35% (nd) |  |
| count_statuses | 22.5 | 21.2 | 21.4 | 21.5 | 21.4 | 21.3 | 35.7 | 44.6 | -2% (nd) | -2% (nd) | -40% | -40% | +23% | +22% |  |
| count_partitions | 19.7 | 19.5 | 4.286 | 4.263 | 4.255 | 4.310 | 8.739 | 39.4 | -78% | -78% | -51% | -51% | -2% (nd) | -2% (nd) |  |
| oldest_created | 31.6 | 31.4 | 0.207 | 0.215 | 0.326 | 0.314 | 2400.8 | 58.1 | -99% | -99% | -100% | -100% | -100% | -100% |  |
| oldest_due_strict | 0.601 | 0.575 | 0.575 | 0.576 | 0.576 | 0.583 | 2595.8 | 1.077 | -2% (nd) | -1% (nd) | -100% | -100% | -100% | -100% |  |
| oldest_due_nonstrict | 29.4 | 29.0 | 0.340 | 0.324 | 0.327 | 0.336 | 12.8 | 56.9 | -99% | -99% | -97% | -97% | -95% | -95% |  |
| count_new | 18.9 | 18.6 | 18.7 | 18.8 | 18.7 | 18.6 | 35.6 | 38.4 | +0% (nd) | -1% (nd) | -47% | -48% | +8% (nd) | +7% (nd) |  |
| count_failed | 0.094 | 0.103 | 0.096 | 0.095 | 0.095 | 0.095 | 0.118 | 0.127 | -3% (nd) | -4% (nd) | -19% (nd) | -19% (nd) | +4% (nd) | +3% (nd) |  |
| count_completed | 30.6 | 30.9 | 30.3 | 30.4 | 30.1 | 30.6 | 57.4 | 62.1 | -1% (nd) | -1% (nd) | -47% | -47% | +7% (nd) | +7% (nd) |  |
| count_partition_single | 17.3 | 17.1 | 0.281 | 0.294 | 0.297 | 0.294 | 0.537 | 35.3 | -98% | -98% | -46% | -45% | +10% (nd) | +13% (nd) |  |

### S8-S6

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.743 | 0.742 | 0.710 | 0.705 | 0.690 | 0.694 | 4.807 | 0.717 | -5% (nd) | -7% (nd) | -85% | -86% | -86% | -86% |  |
| keys_nonstrict | 0.773 | 0.764 | 0.766 | 0.740 | 0.730 | 0.739 | 3.159 | 0.769 | -2% (nd) | -4% (nd) | -76% | -77% | -76% | -77% |  |
| pages | 0.315 | 0.312 | 0.319 | 0.348 | 0.319 | 0.311 | 0.370 | 0.315 | +6% (nd) | +0% (nd) | -10% (nd) | -15% | -10% (nd) | -15% |  |
| insert | 0.424 | 0.418 | 0.426 | 0.441 | 0.424 | 0.350 | 0.577 | 0.364 | +3% (nd) | -8% (nd) | -25% (nd) | -33% (nd) | -35% (nd) | -42% (nd) |  |
| upd_complete | 0.623 | 0.612 | 0.645 | 0.649 | 0.647 | 0.539 | 0.469 | 0.573 | +5% (nd) | -4% (nd) | +38% (nd) | +26% (nd) | +28% (nd) | +17% (nd) |  |
| upd_retry | 0.382 | 0.376 | 0.397 | 0.378 | 0.381 | 0.302 | 0.247 | 0.345 | +2% (nd) | -10% (nd) | +57% (nd) | +38% (nd) | +43% (nd) | +26% (nd) |  |
| upd_fail | 0.284 | 0.191 | 0.236 | 0.320 | 0.291 | 0.199 | 0.215 | 0.273 | +17% (nd) | +3% (nd) | +29% (nd) | +14% (nd) | +48% (nd) | +31% (nd) |  |
| delete_by_id | 0.431 | 0.484 | 0.434 | 0.404 | 0.442 | 0.395 | 0.379 | 0.416 | -8% (nd) | -9% (nd) | +11% (nd) | +10% (nd) | +1% (nd) | +0% (nd) |  |
| count_statuses | 21.3 | 21.5 | 23.3 | 23.2 | 25.4 | 25.6 | 17.7 | 22.5 | +9% (nd) | +19% | +31% | +44% | +38% | +51% |  |
| count_partitions | 19.7 | 19.7 | 6.360 | 6.157 | 6.176 | 6.157 | 6.643 | 21.3 | -68% | -69% | -6% (nd) | -7% (nd) | +2% (nd) | +0% (nd) |  |
| oldest_created | 33.1 | 33.1 | 0.224 | 0.226 | 0.336 | 0.331 | 4048.6 | 33.8 | -99% | -99% | -100% | -100% | -100% | -100% |  |
| oldest_due_strict | 0.417 | 0.414 | 0.407 | 0.392 | 0.390 | 0.408 | 4.303 | 0.415 | -4% (nd) | -4% (nd) | -91% | -91% | -91% | -91% |  |
| oldest_due_nonstrict | 1.146 | 1.126 | 0.350 | 0.327 | 0.320 | 0.314 | 0.544 | 1.185 | -70% | -72% | -38% | -42% | -35% | -39% |  |
| count_new | 18.6 | 18.7 | 20.7 | 20.8 | 22.8 | 23.2 | 17.7 | 19.7 | +11% | +23% | +18% | +30% | +24% | +38% |  |
| count_failed | 0.093 | 0.083 | 0.080 | 0.092 | 0.091 | 0.077 | 0.073 | 0.080 | -2% (nd) | -5% (nd) | +17% (nd) | +14% (nd) | +6% (nd) | +4% (nd) |  |
| count_completed | 80.6 | 80.2 | 80.6 | 79.7 | 80.4 | 79.6 | 75.7 | 84.3 | -0% (nd) | -0% (nd) | +6% (nd) | +6% (nd) | +11% | +11% |  |
| count_partition_single | 17.4 | 17.1 | 0.162 | 0.161 | 0.176 | 0.177 | 0.174 | 17.6 | -99% | -99% | -7% (nd) | +1% (nd) | -5% (nd) | +4% (nd) |  |

### S8-S7

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.488 | 0.487 | 0.450 | 0.458 | 0.457 | 0.443 | 0.554 | 0.450 | -7% (nd) | -8% (nd) | -18% | -19% | -24% | -25% |  |
| keys_nonstrict | 0.511 | 0.509 | 0.488 | 0.495 | 0.495 | 0.478 | 5.866 | 0.487 | -4% (nd) | -5% (nd) | -92% | -92% | -92% | -92% |  |
| pages | 0.341 | 0.319 | 0.318 | 0.327 | 0.330 | 0.315 | 0.354 | 0.317 | -2% (nd) | -2% (nd) | -9% (nd) | -9% (nd) | -13% | -13% |  |
| insert | 0.443 | 0.414 | 0.404 | 0.392 | 0.410 | 0.419 | 0.498 | 0.426 | -7% (nd) | -3% (nd) | -20% (nd) | -17% (nd) | -20% (nd) | -17% (nd) |  |
| upd_complete | 0.492 | 0.514 | 0.491 | 0.484 | 0.614 | 0.592 | 0.770 | 0.425 | -3% (nd) | +20% (nd) | -37% (nd) | -22% (nd) | -46% (nd) | -34% (nd) |  |
| upd_retry | 0.245 | 0.248 | 0.254 | 0.242 | 0.271 | 0.277 | 0.344 | 0.181 | +1% (nd) | +11% (nd) | -28% (nd) | -20% (nd) | -47% (nd) | -41% (nd) |  |
| upd_fail | 0.284 | 0.292 | 0.211 | 0.214 | 0.288 | 0.280 | 0.373 | 0.254 | -26% (nd) | -1% (nd) | -43% (nd) | -24% (nd) | -50% (nd) | -33% (nd) |  |
| delete_by_id | 0.352 | 0.314 | 0.340 | 0.338 | 0.387 | 0.386 | 0.360 | 0.320 | +2% (nd) | +16% (nd) | -6% (nd) | +7% (nd) | -10% (nd) | +3% (nd) |  |
| count_statuses | 68.2 | 71.3 | 68.4 | 69.6 | 65.4 | 66.1 | 49.0 | 70.4 | -1% (nd) | -6% (nd) | +41% | +34% | +42% | +36% |  |
| count_partitions | 1.129 | 1.147 | 0.548 | 0.549 | 0.570 | 0.532 | 0.563 | 1.169 | -52% | -52% | -3% (nd) | -2% (nd) | +0% (nd) | +1% (nd) |  |
| oldest_created | 1.843 | 1.865 | 0.193 | 0.218 | 0.300 | 0.292 | 3.086 | 1.909 | -89% | -84% | -93% | -90% | -93% | -90% |  |
| oldest_due_strict | 0.313 | 0.324 | 0.301 | 0.319 | 0.311 | 0.307 | 7.833 | 0.315 | -3% (nd) | -3% (nd) | -96% | -96% | -96% | -96% |  |
| oldest_due_nonstrict | 1.922 | 1.906 | 0.302 | 0.314 | 0.316 | 0.289 | 0.761 | 1.919 | -84% | -84% | -60% | -60% | -59% | -60% |  |
| count_new | 1.030 | 1.029 | 1.029 | 1.034 | 1.028 | 1.010 | 0.954 | 1.024 | +0% (nd) | -1% (nd) | +8% (nd) | +7% (nd) | +8% (nd) | +6% (nd) |  |
| count_failed | 53.7 | 54.0 | 53.6 | 53.7 | 51.2 | 50.5 | 48.0 | 54.0 | -0% (nd) | -6% (nd) | +12% | +6% (nd) | +12% | +6% (nd) |  |
| count_completed | 60.3 | 60.1 | 59.9 | 59.8 | 60.8 | 60.4 | 56.6 | 61.0 | -1% (nd) | +1% (nd) | +6% (nd) | +7% (nd) | +7% (nd) | +9% (nd) |  |
| count_partition_single | 0.949 | 0.957 | 0.086 | 0.100 | 0.100 | 0.084 | 0.091 | 0.951 | -90% | -90% | +2% (nd) | +1% (nd) | +1% (nd) | +0% (nd) |  |

### S16-S2

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.643 | 0.623 | 0.622 | 0.614 | 0.614 | 0.603 | 0.605 | 0.611 | -2% (nd) | -4% (nd) | +2% (nd) | +0% (nd) | -2% (nd) | -3% (nd) |  |
| keys_nonstrict | 0.650 | 0.642 | 0.677 | 0.682 | 0.680 | 0.675 | 0.393 | 0.623 | +5% (nd) | +5% (nd) | +73% | +73% | +67% | +66% |  |
| pages | 0.325 | 0.307 | 0.323 | 0.308 | 0.338 | 0.306 | 0.377 | 0.317 | -0% (nd) | +2% (nd) | -16% | -15% | -16% | -14% |  |
| insert | 0.466 | 0.465 | 0.505 | 0.462 | 0.474 | 0.378 | 0.631 | 0.394 | +4% (nd) | -8% (nd) | -23% (nd) | -33% (nd) | -35% (nd) | -43% (nd) |  |
| upd_complete | 0.330 | 0.338 | 0.393 | 0.346 | 0.359 | 0.258 | 0.418 | 0.269 | +11% (nd) | -8% (nd) | -12% (nd) | -26% (nd) | -29% (nd) | -41% (nd) |  |
| upd_retry | 0.255 | 0.251 | 0.258 | 0.268 | 0.252 | 0.161 | 0.255 | 0.241 | +4% (nd) | -18% (nd) | +3% (nd) | -19% (nd) | -2% (nd) | -23% (nd) |  |
| upd_fail | 0.274 | 0.194 | 0.236 | 0.211 | 0.181 | 0.169 | 0.235 | 0.225 | -4% (nd) | -25% (nd) | -5% (nd) | -26% (nd) | -9% (nd) | -29% (nd) |  |
| delete_by_id | 0.322 | 0.308 | 0.306 | 0.328 | 0.313 | 0.320 | 0.369 | 0.320 | +1% (nd) | +0% (nd) | -14% (nd) | -14% (nd) | -13% (nd) | -13% (nd) |  |
| count_statuses | 2.407 | 2.505 | 2.495 | 2.513 | 2.661 | 2.681 | 1.839 | 2.427 | +2% (nd) | +9% (nd) | +36% | +45% | +35% | +43% |  |
| count_partitions | 0.217 | 0.240 | 0.137 | 0.150 | 0.153 | 0.149 | 0.143 | 0.243 | -37% | -34% | +1% (nd) | +6% (nd) | +7% (nd) | +13% (nd) |  |
| oldest_created | 0.210 | 0.214 | 0.173 | 0.166 | 0.198 | 0.180 | 0.208 | 0.217 | -20% | -11% (nd) | -18% (nd) | -9% (nd) | -16% (nd) | -7% (nd) |  |
| oldest_due_strict | 0.458 | 0.451 | 0.494 | 0.495 | 0.490 | 0.475 | 0.434 | 0.476 | +9% (nd) | +6% (nd) | +14% | +11% | +19% | +16% |  |
| oldest_due_nonstrict | 0.239 | 0.257 | 0.211 | 0.203 | 0.202 | 0.180 | 0.179 | 0.256 | -17% | -23% | +15% (nd) | +6% (nd) | +19% (nd) | +10% (nd) |  |
| count_new | 0.194 | 0.222 | 0.199 | 0.210 | 0.200 | 0.210 | 0.196 | 0.202 | -2% (nd) | -1% (nd) | +5% (nd) | +5% (nd) | +1% (nd) | +2% (nd) |  |
| count_failed | 1.732 | 1.739 | 1.876 | 1.868 | 2.001 | 2.038 | 1.644 | 1.790 | +8% (nd) | +16% | +14% | +23% | +17% | +27% |  |
| count_completed | 98.5 | 99.2 | 98.6 | 98.5 | 98.4 | 98.7 | 91.4 | 102.0 | -0% (nd) | -0% (nd) | +8% (nd) | +8% (nd) | +11% | +11% |  |
| count_partition_single | 0.197 | 0.202 | 0.091 | 0.099 | 0.102 | 0.089 | 0.089 | 0.209 | -52% | -52% | +7% (nd) | +7% (nd) | +12% (nd) | +13% (nd) |  |

### S16-S4

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.459 | 0.454 | 0.441 | 0.439 | 0.422 | 0.429 | 0.530 | 0.440 | -4% (nd) | -7% (nd) | -17% | -20% | -20% | -23% |  |
| keys_nonstrict | 0.478 | 0.468 | 0.481 | 0.468 | 0.460 | 0.464 | 5123.7 | 0.467 | +0% (nd) | -2% (nd) | -100% | -100% | -100% | -100% |  |
| pages | 0.350 | 0.331 | 0.340 | 0.338 | 0.325 | 0.337 | 0.315 | 0.321 | -0% (nd) | -3% (nd) | +8% (nd) | +5% (nd) | +1% (nd) | -1% (nd) |  |
| insert | 0.510 | 0.498 | 0.515 | 0.464 | 0.508 | 0.483 | 0.647 | 0.489 | -3% (nd) | -2% (nd) | -24% (nd) | -23% (nd) | -26% (nd) | -26% (nd) |  |
| upd_complete | 0.777 | 0.757 | 0.815 | 0.840 | 0.815 | 0.789 | 0.755 | 0.750 | +8% (nd) | +5% (nd) | +10% (nd) | +6% (nd) | +7% (nd) | +4% (nd) |  |
| upd_retry | 0.415 | 0.437 | 0.418 | 0.409 | 0.418 | 0.428 | 0.322 | 0.406 | -3% (nd) | -1% (nd) | +28% (nd) | +31% (nd) | +23% (nd) | +25% (nd) |  |
| upd_fail | 0.309 | 0.313 | 0.330 | 0.179 | 0.173 | 0.313 | 0.290 | 0.251 | -18% (nd) | -22% (nd) | -12% (nd) | -16% (nd) | -29% (nd) | -32% (nd) |  |
| delete_by_id | 0.476 | 0.531 | 0.482 | 0.437 | 0.407 | 0.503 | 0.466 | 0.466 | -9% (nd) | -10% (nd) | -1% (nd) | -2% (nd) | -9% (nd) | -10% (nd) |  |
| count_statuses | 107.0 | 117.1 | 106.7 | 107.3 | 106.5 | 107.0 | 85.3 | 107.0 | -5% (nd) | -5% (nd) | +25% | +25% | +20% | +20% |  |
| count_partitions | 93.3 | 107.4 | 7.348 | 7.328 | 7.538 | 7.407 | 7.467 | 93.6 | -93% | -93% | -2% (nd) | +0% (nd) | -8% (nd) | -7% (nd) |  |
| oldest_created | 5024.9* | 4820.8* | 0.181 | 0.164 | 0.183 | 0.162 | 5128.5 | 5147.1 | -100% | -100% | -100% | -100% | -100% | -100% |  |
| oldest_due_strict | 0.296 | 0.304 | 0.276 | 0.282 | 0.292 | 0.273 | 7607.6 | 0.303 | -7% (nd) | -6% (nd) | -100% | -100% | -100% | -100% |  |
| oldest_due_nonstrict | 5035.6* | 4813.4* | 0.215 | 0.202 | 0.222 | 0.205 | 11.4 | 4949.9 | -100% | -100% | -98% | -98% | -98% | -98% |  |
| count_new | 92.6 | 106.0 | 92.9 | 94.0 | 92.6 | 93.1 | 85.2 | 92.7 | -6% (nd) | -6% (nd) | +10% (nd) | +9% (nd) | +2% (nd) | +2% (nd) |  |
| count_failed | 0.082 | 0.097 | 0.095 | 0.079 | 0.082 | 0.077 | 0.084 | 0.088 | -3% (nd) | -11% (nd) | +4% (nd) | -5% (nd) | +2% (nd) | -6% (nd) |  |
| count_completed | 100.5 | 105.1 | 100.2 | 102.1 | 100.2 | 101.9 | 93.1 | 101.8 | -2% (nd) | -2% (nd) | +9% (nd) | +9% (nd) | +8% (nd) | +7% (nd) |  |
| count_partition_single | 85.6 | 97.0 | 0.477 | 0.491 | 0.500 | 0.483 | 0.488 | 85.6 | -99% | -99% | -1% (nd) | +1% (nd) | -7% (nd) | -6% (nd) |  |

### S1

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.635 | 0.635 | 0.606 | 0.605 | 0.609 | 0.616 | 0.948 | 0.601 | -5% (nd) | -4% (nd) | -36% | -35% | -40% | -39% |  |
| keys_nonstrict | 0.624 | 0.624 | 0.604 | 0.596 | 0.615 | 0.626 | 1.105 | 0.623 | -4% (nd) | -1% (nd) | -46% | -44% | -46% | -44% |  |
| pages | 0.299 | 0.305 | 0.301 | 0.298 | 0.323 | 0.301 | 0.390 | 0.299 | -1% (nd) | +3% (nd) | -23% | -20% | -24% | -21% |  |
| insert | 0.290 | 0.206 | 0.219 | 0.221 | 0.218 | 0.227 | 0.256 | 0.226 | -11% (nd) | -10% (nd) | -14% (nd) | -13% (nd) | -22% (nd) | -21% (nd) |  |
| upd_complete | 0.275 | 0.174 | 0.200 | 0.190 | 0.215 | 0.212 | 0.244 | 0.211 | -13% (nd) | -5% (nd) | -20% (nd) | -13% (nd) | -25% (nd) | -18% (nd) |  |
| upd_retry | 0.243 | 0.143 | 0.142 | 0.138 | 0.144 | 0.150 | 0.187 | 0.137 | -27% (nd) | -24% (nd) | -25% (nd) | -21% (nd) | -47% (nd) | -44% (nd) |  |
| upd_fail | 0.243 | 0.147 | 0.160 | 0.159 | 0.173 | 0.172 | 0.185 | 0.158 | -18% (nd) | -12% (nd) | -14% (nd) | -7% (nd) | -31% (nd) | -25% (nd) |  |
| delete_by_id | 0.077 | 0.086 | 0.083 | 0.074 | 0.080 | 0.076 | 0.080 | 0.074 | -4% (nd) | -4% (nd) | -2% (nd) | -3% (nd) | -10% (nd) | -11% (nd) |  |
| count_statuses | 0.119 | 0.129 | 0.116 | 0.115 | 0.112 | 0.110 | 0.178 | 0.122 | -7% (nd) | -10% (nd) | -35% | -38% | -36% | -39% |  |
| count_partitions | 0.301 | 0.309 | 0.305 | 0.299 | 0.293 | 0.289 | 0.360 | 0.297 | -1% (nd) | -5% (nd) | -16% | -19% | -18% | -21% |  |
| oldest_created | 0.319 | 0.336 | 0.279 | 0.274 | 0.271 | 0.274 | 0.367 | 0.318 | -16% | -17% | -25% | -26% | -27% | -28% |  |
| oldest_due_strict | 0.403 | 0.407 | 0.402 | 0.413 | 0.391 | 0.385 | 1.041 | 0.412 | +1% (nd) | -4% (nd) | -61% | -63% | -60% | -62% |  |
| oldest_due_nonstrict | 0.465 | 0.458 | 0.406 | 0.421 | 0.426 | 0.400 | 0.548 | 0.476 | -10% | -11% | -25% | -25% | -22% | -22% |  |
| count_new | 0.102 | 0.115 | 0.103 | 0.102 | 0.102 | 0.107 | 0.096 | 0.108 | -6% (nd) | -4% (nd) | +7% (nd) | +9% (nd) | +7% (nd) | +9% (nd) |  |
| count_failed | 0.082 | 0.094 | 0.086 | 0.086 | 0.081 | 0.092 | 0.082 | 0.090 | -2% (nd) | -2% (nd) | +4% (nd) | +5% (nd) | +7% (nd) | +8% (nd) |  |
| count_completed | 1.371 | 1.362 | 1.368 | 1.341 | 1.252 | 1.239 | 0.981 | 1.357 | -1% (nd) | -9% (nd) | +38% | +27% | +37% | +26% |  |
| count_partition_single | 0.105 | 0.113 | 0.084 | 0.081 | 0.099 | 0.085 | 0.083 | 0.106 | -24% (nd) | -16% (nd) | +0% (nd) | +12% (nd) | -2% (nd) | +9% (nd) |  |

### S2

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.647 | 0.633 | 0.619 | 0.616 | 0.588 | 0.610 | 1.183 | 0.611 | -4% (nd) | -6% (nd) | -48% | -49% | -50% | -52% |  |
| keys_nonstrict | 0.647 | 0.640 | 0.621 | 0.627 | 0.604 | 0.623 | 3.111 | 0.637 | -3% (nd) | -5% (nd) | -80% | -80% | -80% | -80% |  |
| pages | 0.332 | 0.311 | 0.325 | 0.323 | 0.310 | 0.332 | 0.377 | 0.322 | +1% (nd) | -0% (nd) | -14% | -15% | -14% | -15% |  |
| insert | 0.353 | 0.410 | 0.423 | 0.410 | 0.395 | 0.399 | 0.463 | 0.382 | +9% (nd) | +4% (nd) | -10% (nd) | -14% (nd) | -10% (nd) | -14% (nd) |  |
| upd_complete | 0.256 | 0.359 | 0.411 | 0.430 | 0.349 | 0.376 | 0.438 | 0.341 | +37% (nd) | +18% (nd) | -4% (nd) | -17% (nd) | +7% (nd) | -8% (nd) |  |
| upd_retry | 0.166 | 0.260 | 0.259 | 0.249 | 0.260 | 0.248 | 0.216 | 0.241 | +19% (nd) | +19% (nd) | +18% (nd) | +18% (nd) | +33% (nd) | +33% (nd) |  |
| upd_fail | 0.188 | 0.189 | 0.287 | 0.199 | 0.264 | 0.182 | 0.212 | 0.220 | +29% (nd) | +18% (nd) | +15% (nd) | +5% (nd) | +34% (nd) | +23% (nd) |  |
| delete_by_id | 0.321 | 0.324 | 0.328 | 0.337 | 0.331 | 0.339 | 0.476 | 0.330 | +3% (nd) | +4% (nd) | -30% (nd) | -30% (nd) | -29% (nd) | -28% (nd) |  |
| count_statuses | 2.425 | 2.353 | 2.517 | 2.505 | 2.670 | 2.670 | 1.812 | 2.376 | +5% (nd) | +12% | +39% | +47% | +38% | +47% |  |
| count_partitions | 0.411 | 0.432 | 0.441 | 0.451 | 0.442 | 0.440 | 1.466 | 0.418 | +6% (nd) | +5% (nd) | -70% | -70% | -70% | -70% |  |
| oldest_created | 0.524 | 0.520 | 0.276 | 0.283 | 0.284 | 0.279 | 1.491 | 0.531 | -46% | -46% | -81% | -81% | -81% | -81% |  |
| oldest_due_strict | 0.392 | 0.394 | 0.399 | 0.402 | 0.411 | 0.401 | 3.763 | 0.399 | +2% (nd) | +3% (nd) | -89% | -89% | -89% | -89% |  |
| oldest_due_nonstrict | 0.629 | 0.642 | 0.414 | 0.413 | 0.432 | 0.406 | 1.003 | 0.649 | -35% | -34% | -59% | -58% | -58% | -57% |  |
| count_new | 0.214 | 0.217 | 0.197 | 0.213 | 0.210 | 0.216 | 0.184 | 0.197 | -5% (nd) | -1% (nd) | +11% (nd) | +15% (nd) | +1% (nd) | +5% (nd) |  |
| count_failed | 1.892 | 1.738 | 1.868 | 1.860 | 2.021 | 1.986 | 1.627 | 1.736 | +3% (nd) | +10% | +15% | +23% | +10% (nd) | +18% |  |
| count_completed | 107.1 | 98.8 | 98.4 | 98.9 | 98.5 | 98.7 | 91.2 | 100.9 | -4% (nd) | -4% (nd) | +8% (nd) | +8% (nd) | +6% (nd) | +6% (nd) |  |
| count_partition_single | 0.198 | 0.198 | 0.086 | 0.092 | 0.095 | 0.092 | 0.092 | 0.201 | -55% | -53% | -3% (nd) | +2% (nd) | -2% (nd) | +3% (nd) |  |

### S4

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | over 10 s | over 10 s | 0.666 | 0.668 | 0.655 | 0.650 | 1.597 | - | ref over limit (cand 0.67 ms) | ref over limit (cand 0.65 ms) | -58% | -59% | n/a | n/a |  |
| keys_nonstrict | over 10 s | over 10 s | 0.660 | 0.666 | 0.695 | 0.671 | 90881.5 | 96903.2 | ref over limit (cand 0.66 ms) | ref over limit (cand 0.68 ms) | -100% | -100% | n/a | n/a |  |
| pages | 0.369 | 0.348 | 0.332 | 0.326 | 0.354 | 0.318 | 0.327 | - | -8% (nd) | -6% (nd) | +1% (nd) | +3% (nd) | n/a | n/a |  |
| insert | 0.682 | 0.619 | 0.494 | 0.489 | 0.517 | 0.496 | 0.571 | 0.677 | -24% (nd) | -22% (nd) | -14% (nd) | -11% (nd) | -10% (nd) | -8% (nd) |  |
| upd_complete | 0.758 | 0.775 | 0.802 | 0.791 | 0.771 | 0.830 | 0.715 | 0.775 | +4% (nd) | +4% (nd) | +11% (nd) | +12% (nd) | +13% (nd) | +13% (nd) |  |
| upd_retry | 0.443 | 0.461 | 0.406 | 0.421 | 0.418 | 0.407 | 0.340 | 0.419 | -9% (nd) | -9% (nd) | +22% (nd) | +21% (nd) | +13% (nd) | +12% (nd) |  |
| upd_fail | 0.326 | 0.330 | 0.164 | 0.289 | 0.313 | 0.152 | 0.230 | 0.346 | -31% (nd) | -29% (nd) | -2% (nd) | +1% (nd) | +4% (nd) | +6% (nd) |  |
| delete_by_id | 0.544 | 0.533 | 0.384 | 0.422 | 0.446 | 0.386 | 0.524 | 0.559 | -25% (nd) | -23% (nd) | -23% (nd) | -21% (nd) | -20% (nd) | -18% (nd) |  |
| count_statuses | 117.0 | 117.4 | 106.5 | 107.0 | 106.0 | 107.6 | 85.5 | 106.9 | -9% (nd) | -9% (nd) | +25% | +25% | +14% | +14% |  |
| count_partitions | 129.2 | 129.5 | 118.8 | 119.3 | 118.7 | 119.3 | 359.1 | 111.9 | -8% (nd) | -8% (nd) | -67% | -67% | -71% | -71% |  |
| oldest_created | over 10 s | over 10 s | 0.345 | 0.339 | 0.331 | 0.326 | 85001.4 | 85661.0 | ref over limit (cand 0.34 ms) | ref over limit (cand 0.33 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_strict | over 10 s | over 10 s | 0.428 | 0.418 | 0.424 | 0.416 | 20629.8 | - | ref over limit (cand 0.42 ms) | ref over limit (cand 0.42 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_nonstrict | over 10 s | over 10 s | 0.388 | 0.368 | 0.376 | 0.367 | 78865.5 | 81647.9 | ref over limit (cand 0.38 ms) | ref over limit (cand 0.37 ms) | -100% | -100% | n/a | n/a |  |
| count_new | 101.5 | 103.2 | 92.7 | 93.2 | 93.0 | 93.4 | 85.4 | 92.7 | -9% (nd) | -9% (nd) | +9% (nd) | +9% (nd) | -1% (nd) | -1% (nd) |  |
| count_failed | 0.083 | 0.077 | 0.082 | 0.081 | 0.083 | 0.077 | 0.084 | 0.087 | +2% (nd) | +0% (nd) | -3% (nd) | -5% (nd) | +6% (nd) | +4% (nd) |  |
| count_completed | 100.7 | 102.1 | 100.6 | 101.5 | 100.4 | 101.8 | 96.5 | 101.7 | -0% (nd) | -0% (nd) | +5% (nd) | +5% (nd) | +5% (nd) | +5% (nd) |  |
| count_partition_single | 96.5 | 96.3 | 0.485 | 0.487 | 0.486 | 0.486 | 0.489 | 85.5 | -99% | -99% | -1% (nd) | -1% (nd) | -12% | -12% |  |

### S4-b100

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | over 10 s | over 10 s | 0.989 | 0.978 | 0.961 | 0.950 | 2.041 | - | ref over limit (cand 0.98 ms) | ref over limit (cand 0.96 ms) | -52% | -53% | n/a | n/a |  |
| keys_nonstrict | over 10 s | over 10 s | 1.094 | 1.099 | 1.093 | 1.062 | 84668.6 | 106128.4 | ref over limit (cand 1.10 ms) | ref over limit (cand 1.08 ms) | -100% | -100% | n/a | n/a |  |
| pages | 1.229 | 1.217 | 1.140 | 1.127 | 1.180 | 1.128 | 1.417 | - | -7% (nd) | -6% (nd) | -20% | -19% | n/a | n/a |  |
| insert | 0.637 | 0.626 | 0.480 | 0.508 | 0.502 | 0.524 | 0.573 | 0.693 | -22% (nd) | -19% (nd) | -14% (nd) | -10% (nd) | -5% (nd) | -2% (nd) |  |
| upd_complete | 0.725 | 0.749 | 0.815 | 0.771 | 0.767 | 0.858 | 0.767 | 0.811 | +8% (nd) | +10% (nd) | +3% (nd) | +6% (nd) | +14% (nd) | +17% (nd) |  |
| upd_retry | 0.425 | 0.424 | 0.401 | 0.401 | 0.406 | 0.422 | 0.401 | 0.428 | -6% (nd) | -2% (nd) | -0% (nd) | +3% (nd) | +1% (nd) | +4% (nd) |  |
| upd_fail | 0.330 | 0.322 | 0.297 | 0.170 | 0.161 | 0.320 | 0.283 | 0.341 | -28% (nd) | -26% (nd) | -17% (nd) | -15% (nd) | -14% (nd) | -11% (nd) |  |
| delete_by_id | 0.523 | 0.534 | 0.390 | 0.373 | 0.387 | 0.430 | 0.579 | 0.552 | -28% (nd) | -23% (nd) | -34% (nd) | -30% (nd) | -31% (nd) | -26% (nd) |  |
| count_statuses | 106.8 | 116.1 | 106.8 | 106.8 | 106.6 | 106.2 | 84.8 | 107.3 | -4% (nd) | -5% (nd) | +26% | +25% | +21% | +21% |  |
| count_partitions | 108.8 | 129.1 | 120.2 | 119.4 | 119.5 | 119.4 | 365.5 | 109.6 | +1% (nd) | +0% (nd) | -67% | -67% | -70% | -70% |  |
| oldest_created | over 10 s | over 10 s | 0.345 | 0.336 | 0.332 | 0.334 | 79511.4 | 86600.9 | ref over limit (cand 0.34 ms) | ref over limit (cand 0.33 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_strict | over 10 s | over 10 s | 0.416 | 0.396 | 0.422 | 0.403 | 19910.7 | - | ref over limit (cand 0.41 ms) | ref over limit (cand 0.41 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_nonstrict | over 10 s | over 10 s | 0.360 | 0.344 | 0.360 | 0.372 | 37942.6 | 83822.1 | ref over limit (cand 0.35 ms) | ref over limit (cand 0.37 ms) | -100% | -100% | n/a | n/a |  |
| count_new | 93.2 | 102.8 | 93.1 | 93.6 | 92.9 | 93.2 | 84.8 | 93.1 | -5% (nd) | -5% (nd) | +10% | +10% (nd) | +5% (nd) | +4% (nd) |  |
| count_failed | 0.074 | 0.071 | 0.081 | 0.085 | 0.082 | 0.088 | 0.069 | 0.081 | +14% (nd) | +17% (nd) | +21% (nd) | +24% (nd) | +35% (nd) | +38% (nd) |  |
| count_completed | 101.1 | 102.2 | 100.7 | 102.0 | 100.4 | 101.1 | 93.1 | 102.2 | -0% (nd) | -1% (nd) | +9% (nd) | +8% (nd) | +9% (nd) | +9% (nd) |  |
| count_partition_single | 85.4 | 96.5 | 0.487 | 0.489 | 0.487 | 0.488 | 0.491 | 86.1 | -99% | -99% | -1% (nd) | -1% (nd) | -6% (nd) | -6% (nd) |  |

### S5

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | over 10 s | over 10 s | 0.629 | 0.625 | 0.613 | 0.645 | 4.678 | 15493.6 | ref over limit (cand 0.63 ms) | ref over limit (cand 0.63 ms) | -87% | -87% | n/a | n/a |  |
| keys_nonstrict | over 10 s | over 10 s | 0.698 | 0.684 | 0.684 | 0.676 | 13860.8 | 16924.5 | ref over limit (cand 0.69 ms) | ref over limit (cand 0.68 ms) | -100% | -100% | n/a | n/a |  |
| pages | over 10 s | over 10 s | 1452.4 | 1434.7 | 1413.2 | 1417.0 | 4456.7 | 1476.2 | ref over limit (cand 1443.56 ms) | ref over limit (cand 1415.07 ms) | -68% | -68% | n/a | n/a |  |
| insert | 0.451 | 0.496 | 0.394 | 0.413 | 0.397 | 0.396 | 0.476 | 0.367 | -15% (nd) | -16% (nd) | -15% (nd) | -17% (nd) | -34% (nd) | -36% (nd) |  |
| upd_complete | 0.593 | 0.641 | 0.649 | 0.688 | 0.626 | 0.650 | 0.781 | 0.448 | +8% (nd) | +3% (nd) | -14% (nd) | -18% (nd) | -38% (nd) | -41% (nd) |  |
| upd_retry | 0.366 | 0.357 | 0.337 | 0.344 | 0.351 | 0.339 | 0.315 | 0.286 | -6% (nd) | -5% (nd) | +8% (nd) | +10% (nd) | -14% (nd) | -13% (nd) |  |
| upd_fail | 0.203 | 0.287 | 0.250 | 0.240 | 0.260 | 0.249 | 0.243 | 0.191 | +0% (nd) | +4% (nd) | +1% (nd) | +5% (nd) | -22% (nd) | -19% (nd) |  |
| delete_by_id | 0.287 | 0.321 | 0.260 | 0.254 | 0.253 | 0.244 | 0.535 | 0.400 | -15% (nd) | -18% (nd) | -52% (nd) | -54% (nd) | -37% (nd) | -39% (nd) |  |
| count_statuses | 21.9 | 21.5 | 21.4 | 21.5 | 21.8 | 21.4 | 17.4 | 21.9 | -1% (nd) | -0% (nd) | +23% | +24% | +24% | +25% |  |
| count_partitions | 22.2 | 22.1 | 27.1 | 27.0 | 25.3 | 25.2 | 13201.2 | 22.1 | +22% | +14% | -100% | -100% | -100% | -100% |  |
| oldest_created | over 10 s | over 10 s | 0.275 | 0.268 | 0.277 | 0.277 | 3027.0 | 14887.6 | ref over limit (cand 0.27 ms) | ref over limit (cand 0.28 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_strict | over 10 s | over 10 s | 0.659 | 0.658 | 0.661 | 0.650 | 3575.8 | 14853.2 | ref over limit (cand 0.66 ms) | ref over limit (cand 0.66 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_nonstrict | over 10 s | over 10 s | 0.419 | 0.403 | 0.414 | 0.397 | 37.7 | 14137.1 | ref over limit (cand 0.41 ms) | ref over limit (cand 0.41 ms) | -99% | -99% | n/a | n/a |  |
| count_new | 19.2 | 18.6 | 18.6 | 18.6 | 18.5 | 18.5 | 17.4 | 18.8 | -2% (nd) | -2% (nd) | +7% (nd) | +7% (nd) | +6% (nd) | +6% (nd) |  |
| count_failed | 0.090 | 0.088 | 0.091 | 0.079 | 0.092 | 0.091 | 0.081 | 0.091 | -4% (nd) | +3% (nd) | +4% (nd) | +12% (nd) | +7% (nd) | +15% (nd) |  |
| count_completed | 31.1 | 30.2 | 29.8 | 30.7 | 29.9 | 29.9 | 28.3 | 30.8 | -1% (nd) | -2% (nd) | +7% (nd) | +6% (nd) | +7% (nd) | +6% (nd) |  |
| count_partition_single | 17.3 | 17.1 | 0.310 | 0.280 | 0.293 | 0.278 | 0.289 | 17.2 | -98% | -98% | +2% (nd) | -1% (nd) | +2% (nd) | -1% (nd) |  |

### S6

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | over 10 s | over 10 s | 0.847 | 0.832 | 0.811 | 0.824 | 401.2 | 15895.3 | ref over limit (cand 0.84 ms) | ref over limit (cand 0.82 ms) | -100% | -100% | n/a | n/a |  |
| keys_nonstrict | over 10 s | over 10 s | 0.862 | 0.842 | 0.822 | 0.834 | 388.8 | 15696.1 | ref over limit (cand 0.85 ms) | ref over limit (cand 0.83 ms) | -100% | -100% | n/a | n/a |  |
| pages | over 10 s | over 10 s | 0.333 | 0.321 | 0.313 | 0.337 | 0.357 | 0.310 | ref over limit (cand 0.33 ms) | ref over limit (cand 0.33 ms) | -8% (nd) | -9% (nd) | n/a | n/a |  |
| insert | 0.423 | 0.481 | 0.438 | 0.427 | 0.433 | 0.424 | 0.459 | 0.491 | -4% (nd) | -5% (nd) | -6% (nd) | -7% (nd) | +2% (nd) | +1% (nd) |  |
| upd_complete | 0.381 | 0.466 | 0.525 | 0.543 | 0.525 | 0.590 | 0.434 | 0.415 | +26% (nd) | +32% (nd) | +23% (nd) | +28% (nd) | +20% (nd) | +26% (nd) |  |
| upd_retry | 0.337 | 0.425 | 0.359 | 0.370 | 0.376 | 0.362 | 0.222 | 0.369 | -4% (nd) | -3% (nd) | +65% (nd) | +67% (nd) | +59% (nd) | +61% (nd) |  |
| upd_fail | 0.212 | 0.309 | 0.310 | 0.325 | 0.308 | 0.297 | 0.198 | 0.299 | +22% (nd) | +16% (nd) | +61% (nd) | +53% (nd) | +85% (nd) | +76% (nd) |  |
| delete_by_id | 0.441 | 0.460 | 0.469 | 0.456 | 0.451 | 0.425 | 0.477 | 0.515 | +3% (nd) | -3% (nd) | -3% (nd) | -8% (nd) | +11% (nd) | +5% (nd) |  |
| count_statuses | 21.5 | 21.5 | 23.8 | 23.4 | 25.7 | 25.7 | 17.7 | 22.5 | +10% | +20% | +33% | +45% | +40% | +52% |  |
| count_partitions | 22.0 | 22.5 | 27.2 | 26.7 | 31.0 | 31.3 | 24.7 | 22.8 | +21% | +40% | +9% (nd) | +26% | +12% | +29% |  |
| oldest_created | over 10 s | over 10 s | 0.289 | 0.286 | 0.284 | 0.263 | 7293.8 | 16566.7 | ref over limit (cand 0.29 ms) | ref over limit (cand 0.27 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_strict | over 10 s | over 10 s | 0.477 | 0.482 | 0.481 | 0.460 | 413.0 | 16522.0 | ref over limit (cand 0.48 ms) | ref over limit (cand 0.47 ms) | -100% | -100% | n/a | n/a |  |
| oldest_due_nonstrict | over 10 s | over 10 s | 0.423 | 0.416 | 0.423 | 0.390 | 1.768 | 16233.0 | ref over limit (cand 0.42 ms) | ref over limit (cand 0.41 ms) | -76% | -77% | n/a | n/a |  |
| count_new | 18.4 | 18.6 | 20.6 | 20.7 | 23.1 | 22.9 | 17.6 | 19.5 | +11% | +24% | +17% | +31% | +23% | +37% |  |
| count_failed | 0.095 | 0.083 | 0.090 | 0.087 | 0.089 | 0.089 | 0.077 | 0.080 | -1% (nd) | +0% (nd) | +16% (nd) | +16% (nd) | +3% (nd) | +4% (nd) |  |
| count_completed | 79.8 | 80.6 | 79.7 | 80.4 | 81.2 | 80.1 | 75.4 | 83.8 | -0% (nd) | +1% (nd) | +6% (nd) | +7% (nd) | +11% | +12% |  |
| count_partition_single | 17.2 | 17.1 | 0.185 | 0.178 | 0.184 | 0.164 | 0.183 | 17.5 | -99% | -99% | -1% (nd) | -5% (nd) | +1% (nd) | -3% (nd) |  |

### S7

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 767.7 | 765.4 | 0.600 | 0.618 | 0.597 | 0.601 | 1.228 | 756.9 | -100% | -100% | -50% | -51% | -51% | -52% |  |
| keys_nonstrict | 767.5 | 765.1 | 0.606 | 0.606 | 0.611 | 0.606 | 751.9 | 764.3 | -100% | -100% | -100% | -100% | -100% | -100% |  |
| pages | 0.323 | 0.333 | 0.316 | 0.331 | 0.312 | 0.316 | 0.337 | 0.318 | -1% (nd) | -4% (nd) | -4% (nd) | -7% (nd) | -7% (nd) | -10% (nd) |  |
| insert | 0.343 | 0.431 | 0.407 | 0.394 | 0.423 | 0.400 | 0.475 | 0.394 | +3% (nd) | +6% (nd) | -16% (nd) | -13% (nd) | -14% (nd) | -12% (nd) |  |
| upd_complete | 0.345 | 0.441 | 0.474 | 0.457 | 0.550 | 0.531 | 0.666 | 0.448 | +18% (nd) | +38% (nd) | -30% (nd) | -19% (nd) | -20% (nd) | -8% (nd) |  |
| upd_retry | 0.249 | 0.314 | 0.247 | 0.241 | 0.299 | 0.318 | 0.331 | 0.254 | -13% (nd) | +10% (nd) | -26% (nd) | -7% (nd) | -34% (nd) | -16% (nd) |  |
| upd_fail | 0.213 | 0.301 | 0.294 | 0.292 | 0.212 | 0.290 | 0.284 | 0.310 | +14% (nd) | -2% (nd) | +3% (nd) | -12% (nd) | +24% (nd) | +7% (nd) |  |
| delete_by_id | 0.395 | 0.429 | 0.323 | 0.330 | 0.426 | 0.464 | 0.468 | 0.417 | -21% (nd) | +8% (nd) | -30% (nd) | -5% (nd) | -29% (nd) | -4% (nd) |  |
| count_statuses | 68.8 | 70.4 | 69.2 | 70.3 | 66.2 | 66.1 | 48.7 | 69.3 | +0% (nd) | -5% (nd) | +43% | +36% | +43% | +35% |  |
| count_partitions | 1.375 | 1.367 | 1.745 | 1.669 | 1.584 | 1.592 | 839.5 | 1.415 | +25% | +16% | -100% | -100% | -100% | -100% |  |
| oldest_created | 781.7 | 787.4 | 0.286 | 0.267 | 0.267 | 0.279 | 840.3 | 772.3 | -100% | -100% | -100% | -100% | -100% | -100% |  |
| oldest_due_strict | 810.3 | 814.4 | 0.406 | 0.382 | 0.397 | 0.403 | 871.1 | 791.8 | -100% | -100% | -100% | -100% | -100% | -100% |  |
| oldest_due_nonstrict | 775.1 | 786.0 | 0.414 | 0.404 | 0.396 | 0.411 | 2.714 | 762.6 | -100% | -100% | -85% | -85% | -85% | -85% |  |
| count_new | 1.008 | 1.022 | 1.039 | 1.010 | 1.019 | 1.030 | 0.950 | 1.015 | +1% (nd) | +1% (nd) | +8% (nd) | +8% (nd) | +8% (nd) | +8% (nd) |  |
| count_failed | 54.2 | 54.1 | 54.1 | 54.3 | 50.6 | 50.9 | 47.8 | 54.1 | +0% (nd) | -6% (nd) | +13% | +6% (nd) | +13% | +6% (nd) |  |
| count_completed | 60.3 | 60.1 | 60.3 | 59.9 | 60.2 | 60.1 | 56.4 | 61.5 | -0% (nd) | -0% (nd) | +6% (nd) | +7% (nd) | +9% (nd) | +9% (nd) |  |
| count_partition_single | 0.954 | 0.955 | 0.100 | 0.083 | 0.087 | 0.097 | 0.086 | 0.939 | -90% | -90% | +6% (nd) | +6% (nd) | +4% (nd) | +5% (nd) |  |

### S9

| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| keys_strict | 0.654 | - | 0.598 | - | 0.593 | - | 1.231 | 0.719 | -9% (nd) | -9% (nd) | -51% | -52% | -47% | -47% |  |
| keys_nonstrict | 0.703 | - | 0.613 | - | 0.611 | - | 3.210 | 0.684 | -13% | -13% | -81% | -81% | -81% | -81% |  |
| pages | 0.333 | - | 0.326 | - | 0.312 | - | 0.362 | 0.358 | -2% (nd) | -6% (nd) | -10% (nd) | -14% | -3% (nd) | -7% (nd) |  |
| insert | 0.388 | - | 0.330 | - | 0.409 | - | 0.341 | 0.408 | -15% (nd) | +5% (nd) | -3% (nd) | +20% (nd) | +2% (nd) | +26% (nd) |  |
| upd_complete | 0.497 | - | 0.454 | - | 0.474 | - | 0.358 | 0.643 | -9% (nd) | -5% (nd) | +27% (nd) | +32% (nd) | +64% (nd) | +71% (nd) |  |
| upd_retry | 0.146 | - | 0.160 | - | 0.156 | - | 0.128 | 0.275 | +10% (nd) | +7% (nd) | +25% (nd) | +22% (nd) | +135% (nd) | +130% (nd) |  |
| upd_fail | 0.316 | - | 0.313 | - | 0.244 | - | 0.144 | 0.502 | -1% (nd) | -23% (nd) | +117% (nd) | +69% (nd) | +245% | +169% (nd) |  |
| delete_by_id | 0.618 | - | 0.598 | - | 0.528 | - | 1.054 | 0.757 | -3% (nd) | -15% (nd) | -43% (nd) | -50% (nd) | -31% (nd) | -39% (nd) |  |
| count_statuses | 4.056 | - | 4.087 | - | 4.126 | - | 3.101 | 4.046 | +1% (nd) | +2% (nd) | +32% | +33% | +31% | +33% |  |
| count_partitions | 0.442 | - | 0.447 | - | 0.439 | - | 1.459 | 0.420 | +1% (nd) | -1% (nd) | -69% | -70% | -71% | -71% |  |
| oldest_created | 0.522 | - | 0.301 | - | 0.293 | - | 1.492 | 0.586 | -42% | -44% | -80% | -80% | -77% | -78% |  |
| oldest_due_strict | 0.405 | - | 0.410 | - | 0.418 | - | 3.802 | 0.479 | +1% (nd) | +3% (nd) | -89% | -89% | -87% | -87% |  |
| oldest_due_nonstrict | 0.638 | - | 0.419 | - | 0.432 | - | 1.055 | 0.714 | -34% | -32% | -60% | -59% | -56% | -54% |  |
| count_new | 0.232 | - | 0.217 | - | 0.198 | - | 0.193 | 0.195 | -6% (nd) | -15% (nd) | +12% (nd) | +3% (nd) | -5% (nd) | -14% (nd) |  |
| count_failed | 3.089 | - | 3.101 | - | 3.139 | - | 2.908 | 3.093 | +0% (nd) | +2% (nd) | +7% (nd) | +8% (nd) | +7% (nd) | +8% (nd) |  |
| count_completed | 88.6 | - | 84.0 | - | 135.9 | - | 125.4 | 89.4 | -5% (nd) | +53% | -33% | +8% (nd) | -32% | +9% (nd) |  |
| count_partition_single | 0.205 | - | 0.096 | - | 0.097 | - | 0.106 | 0.201 | -53% | -53% | -9% (nd) | -8% (nd) | -11% (nd) | -10% (nd) |  |


## 3. Write cost (writecost, solo cells; S2-wc and S4-wc, both passes)

Per phase `redo bytes / pages written / p50 ms / p95 ms / throughput per s`, per write; sizes in MB before -> after. `main` / `main p2` = phase 3 pass 1 / pass 2. Thresholds: redo 12 %, p50 8 %, p95 25 %, pages 64 % (supporting only). The `c-ref` S4-wc pass-1 4-thread insert p95 of 21 ms is an outlier (pass 2: 2.4 ms). The `c-ref` S4-wc completion throughput (51-53/s against 400-580/s for the candidates with the same p50) is a property of that phase's write loop on `c-ref`, not of the write latency; judge by redo and latency.


### S2-wc: redo B/write | pages/write | p50 ms | p95 ms | thr/s

- **main**: ins1t 2396 / 1.35 / 0.72 / 1.05 / 1270 | ins4t 2242 / 1.28 / 1.16 / 1.92 / 3054 | updco 5644 / 4.41 / 0.72 / 1.04 / 107 | updre 2726 / 3.56 / 0.69 / 0.90 / 148 | updfa 4831 / 3.52 / 0.74 / 0.96 / 148 | delby 3012 / 4.62 / 0.65 / 0.80 / 150
  - sizes MB before->after: PRIMARY 1362->1373, partition_status_retry 66->70, record_key_completed_created 95->97, record_key_created 90->90, status_created 62->63, status_retry 62->63, index total 374->383 MB, index count 6, all-phase redo 29 MB
- **main p2**: ins1t 2459 / 1.38 / 0.73 / 1.12 / 1232 | ins4t 2259 / 1.30 / 1.16 / 2.07 / 3014 | updco 5486 / 2.80 / 0.72 / 1.16 / 106 | updre 2822 / 3.28 / 0.72 / 1.07 / 148 | updfa 4641 / 3.50 / 0.73 / 0.89 / 148 | delby 2693 / 5.10 / 0.65 / 0.83 / 149
  - sizes MB before->after: PRIMARY 1362->1374, partition_status_retry 66->70, record_key_completed_created 95->97, record_key_created 90->90, status_created 62->63, status_retry 62->63, index total 374->383 MB, index count 6, all-phase redo 29 MB
- **c-ref p1**: ins1t 2337 / 1.30 / 0.66 / 0.80 / 1442 | ins4t 2248 / 1.24 / 1.14 / 1.83 / 3158 | updco 4652 / 3.10 / 0.62 / 0.82 / 109 | updre 3020 / 2.40 / 0.61 / 0.74 / 151 | updfa 3946 / 2.96 / 0.63 / 0.93 / 150 | delby 2107 / 3.64 / 0.58 / 0.69 / 151
  - sizes MB before->after: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_partition 67->69, status_completed_retry 71->72, index total 348->354 MB, index count 5, all-phase redo 27 MB
- **c-ref p2**: ins1t 2236 / 1.23 / 0.66 / 0.82 / 1442 | ins4t 2347 / 1.27 / 1.15 / 1.83 / 3152 | updco 4596 / 3.11 / 0.63 / 0.84 / 108 | updre 3261 / 2.41 / 0.65 / 0.81 / 150 | updfa 4182 / 2.95 / 0.63 / 0.86 / 150 | delby 2080 / 3.63 / 0.54 / 0.67 / 152
  - sizes MB before->after: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_partition 67->69, status_completed_retry 71->72, index total 348->354 MB, index count 5, all-phase redo 28 MB
- **c-1 p1**: ins1t 2330 / 1.25 / 0.66 / 0.82 / 1438 | ins4t 2374 / 1.27 / 1.16 / 1.85 / 3128 | updco 8708 / 3.71 / 0.69 / 0.94 / 145 | updre 2817 / 2.37 / 0.62 / 0.77 / 150 | updfa 4132 / 3.02 / 0.64 / 0.90 / 150 | delby 5784 / 5.34 / 0.54 / 0.62 / 152
  - sizes MB before->after: PRIMARY 1362->1376, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_retry 71->72, status_partition_created 66->70, index total 347->355 MB, index count 5, all-phase redo 31 MB
- **c-1 p2**: ins1t 2323 / 1.31 / 0.66 / 0.81 / 1435 | ins4t 2328 / 1.26 / 1.16 / 1.86 / 3122 | updco 8461 / 3.68 / 0.72 / 0.98 / 144 | updre 3029 / 2.40 / 0.62 / 0.75 / 151 | updfa 4171 / 3.04 / 0.64 / 0.90 / 150 | delby 5700 / 5.33 / 0.54 / 0.62 / 152
  - sizes MB before->after: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_retry 71->72, status_partition_created 66->69, index total 347->354 MB, index count 5, all-phase redo 30 MB
- **c-2 p1**: ins1t 2271 / 1.25 / 0.71 / 0.94 / 914 | ins4t 2280 / 1.24 / 1.16 / 1.85 / 3154 | updco 9665 / 3.85 / 0.79 / 1.10 / 133 | updre 3142 / 2.42 / 0.66 / 0.79 / 149 | updfa 4150 / 3.01 / 0.69 / 0.88 / 148 | delby 6021 / 5.41 / 0.55 / 0.71 / 152
  - sizes MB before->after: PRIMARY 1362->1373, key_completed_created_id 95->97, status_created_id_partition_retry_key 109->110, status_partition_created 66->70, status_retry_partition 66->67, index total 336->344 MB, index count 5, all-phase redo 30 MB
- **c-2 p2**: ins1t 2279 / 1.24 / 0.66 / 0.81 / 1450 | ins4t 2245 / 1.25 / 1.16 / 1.83 / 3142 | updco 8823 / 3.85 / 0.78 / 1.10 / 132 | updre 3235 / 2.41 / 0.65 / 0.78 / 150 | updfa 4028 / 3.01 / 0.68 / 0.91 / 149 | delby 6195 / 4.79 / 0.58 / 0.72 / 151
  - sizes MB before->after: PRIMARY 1362->1374, key_completed_created_id 95->97, status_created_id_partition_retry_key 109->110, status_partition_created 66->70, status_retry_partition 66->67, index total 336->344 MB, index count 5, all-phase redo 30 MB

### S4-wc: redo B/write | pages/write | p50 ms | p95 ms | thr/s

- **main**: ins1t 2613 / 1.47 / 0.74 / 1.24 / 823 | ins4t 2397 / 1.33 / 1.25 / 2.48 / 1778 | updco 15436 / 6.08 / 1.07 / 2.85 / 350 | updre 3710 / 1.89 / 0.69 / 1.09 / 650 | updfa 3912 / 2.73 / 0.74 / 1.39 / 618 | delby 1656 / 2.13 / 0.73 / 1.17 / 628
  - sizes MB before->after: PRIMARY 2656->2669, partition_status_retry 122->131, record_key_completed_created 181->191, record_key_created 175->176, status_created 113->115, status_retry 113->114, index total 704->728 MB, index count 6, all-phase redo 149 MB
- **main p2**: ins1t 2576 / 1.40 / 0.69 / 0.92 / 1370 | ins4t 2484 / 1.33 / 1.17 / 1.90 / 3047 | updco 14467 / 7.64 / 0.97 / 1.74 / 471 | updre 3710 / 1.76 / 0.67 / 0.86 / 672 | updfa 3864 / 2.79 / 0.72 / 0.95 / 646 | delby 1659 / 1.98 / 0.70 / 0.86 / 757
  - sizes MB before->after: PRIMARY 2656->2669, partition_status_retry 122->131, record_key_completed_created 181->196, record_key_created 175->176, status_created 113->115, status_retry 113->115, index total 704->733 MB, index count 6, all-phase redo 144 MB
- **c-ref p1**: ins1t 5713 / 2.53 / 0.71 / 0.99 / 1100 | ins4t 3079 / 2.16 / 1.41 / 20.98 / 1488 | updco 14514 / 6.67 / 0.91 / 1.45 / 51 | updre 14363 / 3.41 / 0.78 / 1.03 / 452 | updfa 4119 / 1.67 / 0.63 / 0.84 / 524 | delby 1828 / 0.91 / 0.61 / 0.75 / 815
  - sizes MB before->after: PRIMARY 2656->2669, key_completed_created_id 181->198, status_completed_created_id 212->226, status_completed_partition 119->170, status_completed_retry 127->130, index total 640->724 MB, index count 5, all-phase redo 218 MB
- **c-ref p2**: ins1t 5697 / 2.49 / 0.70 / 0.95 / 1316 | ins4t 3134 / 2.14 / 1.30 / 2.44 / 2613 | updco 14730 / 6.15 / 0.91 / 1.32 / 53 | updre 14257 / 3.75 / 0.78 / 1.05 / 617 | updfa 4185 / 1.55 / 0.63 / 0.83 / 603 | delby 1837 / 1.10 / 0.61 / 0.76 / 971
  - sizes MB before->after: PRIMARY 2656->2668, key_completed_created_id 181->197, status_completed_created_id 212->230, status_completed_partition 119->170, status_completed_retry 127->129, index total 640->725 MB, index count 5, all-phase redo 219 MB
- **c-1 p1**: ins1t 2642 / 1.39 / 0.68 / 0.87 / 1392 | ins4t 2370 / 1.33 / 1.16 / 1.90 / 3068 | updco 15101 / 6.96 / 1.01 / 2.17 / 400 | updre 14743 / 3.06 / 0.83 / 1.15 / 391 | updfa 4102 / 2.89 / 0.72 / 0.96 / 644 | delby 2088 / 1.13 / 0.61 / 0.78 / 807
  - sizes MB before->after: PRIMARY 2656->2669, key_completed_created_id 181->192, status_completed_created_id 212->222, status_completed_retry 127->129, status_partition_created 122->130, index total 642->674 MB, index count 5, all-phase redo 205 MB
- **c-1 p2**: ins1t 2641 / 1.49 / 0.69 / 0.89 / 1370 | ins4t 2410 / 1.32 / 1.18 / 2.00 / 3011 | updco 14489 / 8.31 / 0.98 / 2.11 / 519 | updre 14186 / 3.73 / 0.78 / 1.04 / 617 | updfa 4133 / 2.69 / 0.72 / 0.93 / 571 | delby 2100 / 1.05 / 0.61 / 0.78 / 813
  - sizes MB before->after: PRIMARY 2656->2669, key_completed_created_id 181->197, status_completed_created_id 212->230, status_completed_retry 127->130, status_partition_created 122->130, index total 642->686 MB, index count 5, all-phase redo 200 MB
- **c-2 p1**: ins1t 2546 / 1.39 / 0.69 / 0.89 / 1372 | ins4t 2436 / 1.33 / 1.17 / 1.95 / 3048 | updco 14363 / 8.11 / 0.98 / 2.08 / 579 | updre 14414 / 3.71 / 0.79 / 1.08 / 817 | updfa 4027 / 2.93 / 0.72 / 0.96 / 643 | delby 2118 / 0.98 / 0.67 / 0.86 / 644
  - sizes MB before->after: PRIMARY 2656->2669, key_completed_created_id 181->197, status_created_id_partition_retry_key 206->223, status_partition_created 122->130, status_retry_partition 122->124, index total 630->674 MB, index count 5, all-phase redo 200 MB
- **c-2 p2**: ins1t 2632 / 1.48 / 0.69 / 0.90 / 1365 | ins4t 2419 / 1.33 / 1.18 / 1.91 / 3046 | updco 14660 / 8.06 / 0.98 / 1.95 / 432 | updre 14425 / 3.78 / 0.79 / 1.07 / 614 | updfa 4013 / 2.88 / 0.73 / 0.95 / 567 | delby 2105 / 1.05 / 0.61 / 0.78 / 810
  - sizes MB before->after: PRIMARY 2656->2669, key_completed_created_id 181->196, status_created_id_partition_retry_key 206->222, status_partition_created 122->130, status_retry_partition 122->124, index total 630->671 MB, index count 5, all-phase redo 201 MB


## 4. Concurrency (S10; 60 s per cell, both passes)

`processed/s` and `inserted/s` per pass, p50 ms of the keys query, the pages query and the update, global redo MB. `main` = phase 3.


| label | run | processed/s p1 | processed/s p2 | inserted/s p1 | inserted/s p2 | keys p50 p1/p2 | pages p50 p1/p2 | upd p50 p1/p2 | redo MB p1/p2 |
|---|---|---|---|---|---|---|---|---|---|
| S10-S2-p1 | main | 513.6 | 513.5 | 498.0 | 498.1 | 1.29/1.28 | 0.12/0.12 | 0.61/0.60 | 181/185 |
| S10-S2-p1 | c-ref | 513.4 | 513.1 | 498.1 | 498.1 | 0.57/0.56 | 0.18/0.18 | 0.97/0.61 | 183/184 |
| S10-S2-p1 | c-1 | 513.5 | 513.4 | 498.1 | 498.1 | 0.53/0.53 | 0.18/0.18 | 0.68/0.60 | 182/182 |
| S10-S2-p1 | c-2 | 513.5 | 512.8 | 498.1 | 497.5 | 0.52/0.52 | 0.17/0.18 | 0.60/0.65 | 183/180 |
| S10-S2-p1-unthr | main | 342.6 | 394.0 | 2251.6 | 2578.5 | 7.58/7.42 | 0.24/0.21 | 1.20/1.18 | 443/509 |
| S10-S2-p1-unthr | c-ref | 2.0 | 2.2 | 2053.6 | 2175.8 | 1.32/0.71 | 3.43/3.42 | 1.11/1.11 | 338/352 |
| S10-S2-p1-unthr | c-1 | 421.6 | 439.3 | 2378.9 | 2471.8 | 0.58/0.58 | 5.58/5.54 | 1.18/1.17 | 474/493 |
| S10-S2-p1-unthr | c-2 | 448.4 | 418.4 | 2554.2 | 2379.3 | 0.57/0.57 | 5.44/5.52 | 1.16/1.18 | 510/475 |
| S10-S2-p4 | main | 511.9 | 513.1 | 496.7 | 497.6 | 0.59/0.56 | 0.19/0.18 | 0.97/1.08 | 182/181 |
| S10-S2-p4 | c-ref | 513.1 | 512.8 | 497.9 | 497.4 | 0.40/0.41 | 0.17/0.17 | 0.79/1.06 | 184/183 |
| S10-S2-p4 | c-1 | 512.8 | 512.9 | 497.7 | 497.5 | 0.40/0.39 | 0.17/0.17 | 1.07/0.98 | 183/182 |
| S10-S2-p4 | c-2 | 513.0 | 512.9 | 497.3 | 497.3 | 0.39/0.37 | 0.17/0.17 | 0.96/0.66 | 181/183 |
| S10-S2-p4-unthr | main | 968.9 | 981.3 | 2067.3 | 2197.5 | 9.34/9.42 | 0.26/0.25 | 1.27/1.24 | 561/584 |
| S10-S2-p4-unthr | c-ref | 1342.6 | 1349.1 | 2191.5 | 2194.5 | 0.96/0.96 | 2.28/2.62 | 1.22/1.22 | 653/658 |
| S10-S2-p4-unthr | c-1 | 1323.1 | 1323.8 | 2177.6 | 2191.7 | 1.00/1.04 | 2.77/2.33 | 1.22/1.22 | 653/656 |
| S10-S2-p4-unthr | c-2 | 1297.0 | 1268.7 | 2176.8 | 2126.5 | 0.97/0.97 | 2.34/2.61 | 1.22/1.23 | 645/628 |
| S10-S4-p4 | main | 700.7 | 768.1 | 497.8 | 497.8 | 11.82/10.67 | 4.98/4.65 | 1.80/1.62 | 465/485 |
| S10-S4-p4 | c-ref | 883.0 | 897.8 | 493.8 | 494.7 | 10.18/10.23 | 0.84/0.81 | 1.68/1.66 | 567/569 |
| S10-S4-p4 | c-1 | 961.1 | 902.9 | 484.1 | 497.8 | 9.19/9.77 | 0.85/0.83 | 1.67/1.62 | 574/516 |
| S10-S4-p4 | c-2 | 919.9 | 927.8 | 498.0 | 498.0 | 9.56/9.38 | 0.83/0.85 | 1.54/1.57 | 507/522 |
| S10-S4-p4-unthr | main | 370.1 | 387.9 | 1412.5 | 1484.3 | 21.64/20.90 | 7.08/6.75 | 2.91/2.79 | 526/549 |
| S10-S4-p4-unthr | c-ref | 574.6 | 559.0 | 1402.4 | 1401.5 | 16.56/16.58 | 1.21/1.33 | 2.30/2.40 | 524/526 |
| S10-S4-p4-unthr | c-1 | 534.8 | 564.3 | 1584.5 | 1625.9 | 15.69/16.22 | 1.69/1.45 | 2.56/2.43 | 608/587 |
| S10-S4-p4-unthr | c-2 | 556.6 | 571.4 | 1623.3 | 1634.0 | 16.84/15.98 | 1.51/1.36 | 2.47/2.41 | 586/585 |


## 5. S9 (churn, pass 1 only)

500,000 inserts, processing (30 % delete-on-complete, 2 % FAILED, 10 % retried), retention deletes of about 500,000 oldest COMPLETED, `--retention-max-s 2700`; then the P1/P2 bench on the churned table (rows in section 2, label S9).

| run | retention removed | churn seconds | COMPLETED / FAILED / NEW after | index MB after |
|---|---|---|---|---|
| `c-ref` | 500,000 | 292 | 829,213 / 22,827 / 1,200 | PRIMARY 1746, i1 124, i2 147, i3 76, 4th 70 |
| `c-1` | 500,000 | 286 | 829,213 / 22,827 / 1,200 | PRIMARY 1749, i1 122, i2 146, i3 75, 4th (`status_partition_created`) 101 |
| `c-2` | **26,000 (capped at 2700 s)** | 2924 | 1,303,213 / 22,827 / 1,200 | PRIMARY 1720, E5 163, i2 140, `status_retry_partition` 96, 4th 101 |
| `mdb-main` (ph3) | 28,000 (capped) | 2950 | about 1.3M COMPLETED | - |
| `mdb-opt` (ph3) | 500,000 | 311 | 829,213 | - |

`c-2` has no index with `completed_at` after `status`, so the retention select (`WHERE status = 'COMPLETED' AND completed_at < ? ORDER BY completed_at LIMIT 1000`) shows "Creating sort index" in the process list and needs about 5 s per batch (`c-1`, `c-ref`: well under 1 s). P3 is not judged, but it is a visible cost of `c-2`. Because the `c-2` table is 57 % larger afterwards, its S9 read timings (for example `count_completed` 136 vs 84-89 ms) are not like for like; the P1/P2 rows (keys, oldest, pages) do not depend on the number of COMPLETED rows and are comparable. S9 pass 2 was skipped (as in phase 3; repeat in the validation stage).

## 6. Plans (pass 1 `plans/*.summary.txt`; `handlers.csv` next to them)

Queries slower than 5 ms in `c-1` or `c-2` (per label) and the explanation of the remaining regressions:

- `keys_strict` S8-S5 (54 / 50 ms against `c-ref` 108 ms, main 15 s): the outer scan visits all 199,980 NEW rows of the owned partitions in the hot keys; `c-ref` additionally examines 52k rows in the earlier-probe, the candidates probe the key index with 17 rows. `pages` S8-S5 / S5 1.3-1.5 s in all runs: 18,000-20,000 rows examined on `key_completed_created_id` (the page query, unchanged by the candidates; main 4.9 s).
- `count_statuses` (P2): the same cost class in all runs (a covering scan of 200,000-1,000,000 NEW/FAILED index entries, 21-107 ms); the index chosen differs (`c-ref`: `status_completed_partition` / `status_completed_retry`; `c-1`: `status_partition_created` or `status_completed_retry`; `c-2`: `status_retry_partition` / the 6-column E5 index); a wider index is slightly slower (`c-2` up to +19 %).
- `count_partitions` (P2): at partial ownership the candidates use `status_partition_created` as a range over the owned partitions (examined 250,815 / 62,536 rows instead of 1,000,000: 29 / 7.3 ms against 115 / 93 ms). At full ownership (S4, S5, S6, S7) the planner scans the whole NEW range and picks another index than `c-ref`: `c-1` `status_completed_retry`, `c-2` the 6-column E5 index, with the same rows examined and a 14-40 % slower scan (S6 22.0 -> 27.2 / 31.0 ms). Without an index hint (out of scope) there is no cheaper full-ownership plan.

#### Plans per query (index key parts, rows examined)


#### S8-S4
- `count_statuses` ms c-ref/c-1/c-2: 117.5 / 106.6 / 106.7
  - c-ref: outbox_record:range:status_completed_retry[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status] ex=1000000
  - c-2: outbox_record:range:status_retry_partition[status] ex=1000000
- `count_partitions` ms c-ref/c-1/c-2: 115.3 / 29.2 / 29.6
  - c-ref: outbox_record:ref:status_completed_retry[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status,partition_no] ex=250815
  - c-2: outbox_record:range:status_partition_created[status,partition_no] ex=250815

#### S8-S5
- `keys_strict` ms c-ref/c-1/c-2: 108.6 / 53.8 / 49.5
  - c-ref: o:ref:status_completed_created_id[status,completed_at] ex=199980 ; earlier:ref:key_completed_created_id[record_key,completed_at] ex=51983.000
  - c-1: o:ref:status_completed_created_id[status,completed_at] ex=199980 ; e:ref:key_completed_created_id[record_key,completed_at] ex=17
  - c-2: o:ref:status_created_id_partition_retry_key[status] ex=199980 ; e:ref:key_completed_created_id[record_key,completed_at] ex=17
- `pages` ms c-ref/c-1/c-2: 1249.1 / 1254.7 / 1326.9
  - c-ref: outbox_record:range:key_completed_created_id[record_key,completed_at] ex=18000
  - c-1: outbox_record:range:key_completed_created_id[record_key,completed_at] ex=18000
  - c-2: outbox_record:range:key_completed_created_id[record_key,completed_at] ex=18000
- `count_statuses` ms c-ref/c-1/c-2: 22.5 / 21.4 / 21.4
  - c-ref: outbox_record:range:status_completed_partition[status] ex=200000
  - c-1: outbox_record:range:status_partition_created[status] ex=200000
  - c-2: outbox_record:range:status_retry_partition[status] ex=200000

#### S8-S6
- `count_statuses` ms c-ref/c-1/c-2: 21.3 / 23.3 / 25.4
  - c-ref: outbox_record:range:status_completed_partition[status] ex=200000
  - c-1: outbox_record:range:status_completed_retry[status] ex=200000
  - c-2: outbox_record:range:status_created_id_partition_retry_key[status] ex=200000
- `count_partitions` ms c-ref/c-1/c-2: 19.7 / 6.4 / 6.2
  - c-ref: outbox_record:ref:status_completed_partition[status] ex=200000
  - c-1: outbox_record:range:status_partition_created[status,partition_no] ex=50229
  - c-2: outbox_record:range:status_partition_created[status,partition_no] ex=50229

#### S8-S7
- `count_statuses` ms c-ref/c-1/c-2: 68.2 / 68.4 / 65.4
  - c-ref: outbox_record:range:status_completed_retry[status] ex=400000
  - c-1: outbox_record:range:status_completed_retry[status] ex=400000
  - c-2: outbox_record:range:status_retry_partition[status] ex=400000

#### S16-S4
- `count_statuses` ms c-ref/c-1/c-2: 107.0 / 106.7 / 106.5
  - c-ref: outbox_record:range:status_completed_partition[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status] ex=1000000
  - c-2: outbox_record:range:status_retry_partition[status] ex=1000000
- `count_partitions` ms c-ref/c-1/c-2: 93.3 / 7.3 / 7.5
  - c-ref: outbox_record:ref:status_completed_partition[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status,partition_no] ex=62536
  - c-2: outbox_record:range:status_partition_created[status,partition_no] ex=62536

#### S4
- `count_statuses` ms c-ref/c-1/c-2: 117.0 / 106.5 / 106.0
  - c-ref: outbox_record:range:status_completed_retry[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status] ex=1000000
  - c-2: outbox_record:range:status_retry_partition[status] ex=1000000
- `count_partitions` ms c-ref/c-1/c-2: 129.2 / 118.8 / 118.7
  - c-ref: outbox_record:ref:status_completed_retry[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status,partition_no] ex=1000000
  - c-2: outbox_record:range:status_partition_created[status,partition_no] ex=1000000

#### S4-b100
- `count_statuses` ms c-ref/c-1/c-2: 106.8 / 106.8 / 106.6
  - c-ref: outbox_record:range:status_completed_partition[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status] ex=1000000
  - c-2: outbox_record:range:status_retry_partition[status] ex=1000000
- `count_partitions` ms c-ref/c-1/c-2: 108.8 / 120.2 / 119.5
  - c-ref: outbox_record:ref:status_completed_partition[status] ex=1000000
  - c-1: outbox_record:range:status_partition_created[status,partition_no] ex=1000000
  - c-2: outbox_record:range:status_partition_created[status,partition_no] ex=1000000

#### S5
- `pages` ms c-ref/c-1/c-2: over limit / 1452.4 / 1413.2
  - c-ref: outbox_record:ref:key_completed_created_id[record_key,completed_at] ex=-
  - c-1: outbox_record:range:key_completed_created_id[record_key,completed_at] ex=20000
  - c-2: outbox_record:range:key_completed_created_id[record_key,completed_at] ex=20000
- `count_statuses` ms c-ref/c-1/c-2: 21.9 / 21.4 / 21.8
  - c-ref: outbox_record:range:status_completed_partition[status] ex=200000
  - c-1: outbox_record:range:status_partition_created[status] ex=200000
  - c-2: outbox_record:range:status_retry_partition[status] ex=200000
- `count_partitions` ms c-ref/c-1/c-2: 22.2 / 27.1 / 25.3
  - c-ref: outbox_record:ref:status_completed_partition[status] ex=199980
  - c-1: outbox_record:ref:status_completed_retry[status] ex=199980
  - c-2: outbox_record:ref:status_retry_partition[status] ex=199980

#### S6
- `count_statuses` ms c-ref/c-1/c-2: 21.5 / 23.8 / 25.7
  - c-ref: outbox_record:range:status_completed_partition[status] ex=200000
  - c-1: outbox_record:range:status_completed_retry[status] ex=200000
  - c-2: outbox_record:range:status_created_id_partition_retry_key[status] ex=200000
- `count_partitions` ms c-ref/c-1/c-2: 22.0 / 27.2 / 31.0
  - c-ref: outbox_record:ref:status_completed_partition[status] ex=200000
  - c-1: outbox_record:ref:status_completed_retry[status] ex=200000
  - c-2: outbox_record:ref:status_created_id_partition_retry_key[status] ex=200000

#### S7
- `count_statuses` ms c-ref/c-1/c-2: 68.8 / 69.2 / 66.2
  - c-ref: outbox_record:range:status_completed_retry[status] ex=400000
  - c-1: outbox_record:range:status_completed_retry[status] ex=400000
  - c-2: outbox_record:range:status_retry_partition[status] ex=400000


## 7. Equivalence (`results/c/equiv/mariadb/<scenario>/mdb-opt-vs-c-{1,2}.md`)

`HARNESS_SAMPLE_ORDERED=1`, lane 2, v4 ids, run before pass 1: `c-1` and `c-2` against `mdb-opt` on S1, S2, S5, S7. Result: **PASS** for every cell (S1 28 of 28 PASS; S2, S5, S7 26 PASS and 2 SKIP for each candidate: the two `find_*_all` admin queries run only on S1/S3), no WARN, no FAIL. Every read (strict and non-strict keys, pages per key, counts, `count_statuses`, MIN values, find/ids queries) and every write effect agrees; `ks-11` / `kn-14` agree on the key lists on the hot-key S5 and the FAILED S7 data. `failures.txt` is therefore absent.

## 8. Differences between the two candidates (measured)

- Index bytes at load: `c-1` 347 MB (S2), 642 MB (S4); `c-2` 336 MB, 630 MB (-3 % / -2 %); after S2-wc / S4-wc 355 -> 344 MB and 686 -> 671 MB. Table 1362 / 2656 MB in both.
- Write cost S2-wc (redo B/write, pass 1 / pass 2): completion `c-1` 8708 / 8461, `c-2` 9665 / 8823 (+4..11 %, around the 12 % threshold); delete 5784 / 5700 vs 6021 / 6195 (+4..9 %); insert, retry, fail equal. S4-wc: all phases within 3 % apart from delete (2088 / 2100 vs 2118 / 2105). Insert latency and 4-thread throughput equal. The `c-2` S2-wc pass-1 1-thread insert throughput of 914/s (p50 0.71 ms) is a one-off (pass 2: 1450/s, as everyone).
- Reads: `oldest_created` at partial ownership `c-2` +0.09..0.13 ms against `c-1` (both passes, S8-S2, S8-S4, S8-S5, S8-S6, S8-S7; S16 labels 0.18-0.2 ms in both); `count_statuses` `c-2` +6..19 % on S2-class data; `count_partitions` at full ownership `c-2` -7..+14 % against `c-1` (S6 31.0 vs 27.2 ms, S5 25.3 vs 27.1 ms); `keys_*`, `pages`, `oldest_due_*` equal within noise.
- S10: all six cells equal within 10 % between `c-1` and `c-2` (largest: `c-2` S10-S2-p4-unthr 1297 / 1269 vs `c-ref` 1343 / 1349 rec/s; section 4). Retention (P3): section 5.

## 9. Isolation test: read penalty of the 4th index

Data S8-S4, S16-S4, S4, S8-S5; runs `c-1` (spc DDL), `c-1-no4` (`experiments/r2/base/ddl.sql`, the same `forms-a` overlays) and `c-1b` (`c-1` again after `c-1-no4`, a drift check); each cell back to back on the same snapshot, 50 reps after 10 warm-up, `--only` the five queries, limit 60 s, v4 ids; raw `results/c/iso/<run>/mariadb/<label>/`. Bench difference (the 4th index adds 0.03-0.24 ms, the plans and rows examined are identical):


**S8-S4** bench median ms (50 reps, 10 warm-up):

| query | c-1 | c-1-no4 | c-1b (c-1 again) | c-1 - no4 | c-1b - no4 | mean diff |
|---|---|---|---|---|---|---|
| keys_strict | 0.473 | 0.360 | 0.488 | +0.113 | +0.128 | +0.120 (+33%) |
| keys_nonstrict | 0.495 | 0.379 | 0.498 | +0.116 | +0.119 | +0.117 (+31%) |
| oldest_created | 0.285 | 0.167 | 0.300 | +0.118 | +0.133 | +0.125 (+75%) |
| oldest_due_strict | 0.389 | 0.265 | 0.393 | +0.124 | +0.128 | +0.126 (+48%) |
| oldest_due_nonstrict | 0.438 | 0.205 | 0.442 | +0.233 | +0.237 | +0.235 (+115%) |

**S16-S4** bench median ms (50 reps, 10 warm-up):

| query | c-1 | c-1-no4 | c-1b (c-1 again) | c-1 - no4 | c-1b - no4 | mean diff |
|---|---|---|---|---|---|---|
| keys_strict | 0.397 | 0.361 | 0.426 | +0.036 | +0.065 | +0.051 (+14%) |
| keys_nonstrict | 0.417 | 0.352 | 0.427 | +0.065 | +0.075 | +0.070 (+20%) |
| oldest_created | 0.209 | 0.159 | 0.212 | +0.050 | +0.053 | +0.051 (+32%) |
| oldest_due_strict | 0.298 | 0.257 | 0.305 | +0.041 | +0.048 | +0.044 (+17%) |
| oldest_due_nonstrict | 0.225 | 0.180 | 0.230 | +0.045 | +0.050 | +0.048 (+26%) |

**S4** bench median ms (50 reps, 10 warm-up):

| query | c-1 | c-1-no4 | c-1b (c-1 again) | c-1 - no4 | c-1b - no4 | mean diff |
|---|---|---|---|---|---|---|
| keys_strict | 0.631 | 0.546 | 0.622 | +0.085 | +0.076 | +0.080 (+15%) |
| keys_nonstrict | 0.604 | 0.509 | 0.619 | +0.095 | +0.110 | +0.102 (+20%) |
| oldest_created | 0.387 | 0.276 | 0.389 | +0.111 | +0.113 | +0.112 (+41%) |
| oldest_due_strict | 0.433 | 0.374 | 0.439 | +0.059 | +0.065 | +0.062 (+17%) |
| oldest_due_nonstrict | 0.375 | 0.342 | 0.369 | +0.033 | +0.027 | +0.030 (+9%) |

**S8-S5** bench median ms (50 reps, 10 warm-up):

| query | c-1 | c-1-no4 | c-1b (c-1 again) | c-1 - no4 | c-1b - no4 | mean diff |
|---|---|---|---|---|---|---|
| keys_strict | 53.610 | 53.509 | 53.470 | +0.101 | -0.039 | +0.031 (+0%) |
| keys_nonstrict | 0.536 | 0.480 | 0.544 | +0.056 | +0.064 | +0.060 (+13%) |
| oldest_created | 0.244 | 0.183 | 0.255 | +0.061 | +0.072 | +0.067 (+36%) |
| oldest_due_strict | 0.614 | 0.527 | 0.612 | +0.087 | +0.085 | +0.086 (+16%) |
| oldest_due_nonstrict | 0.345 | 0.200 | 0.361 | +0.145 | +0.161 | +0.153 (+76%) |


**Planning time.** `bench plantime` (new harness command, README "MariaDB port"): median of 20 `EXPLAIN FORMAT=JSON` statements per query through the same JDBC path, literal statement and row-limit prefix as the plan capture, on a freshly prepared S4 / S5 run schema for each DDL (`results/c/iso/<run>/mariadb/plantime-*/plantime.csv`). EXPLAIN includes the client round trip and does not execute the query.


| data / ownership | query | EXPLAIN c-1 ms | c-1b | c-1-no4 ms | c-1 - no4 ms |
|---|---|---|---|---|---|
| S4, mod4 (64 partitions) | keys_strict | 0.314 | 0.294 | 0.177 | +0.137 |
| S4, mod4 (64 partitions) | keys_nonstrict | 0.327 | 0.309 | 0.194 | +0.133 |
| S4, mod4 (64 partitions) | oldest_created | 0.260 | 0.252 | 0.154 | +0.106 |
| S4, mod4 (64 partitions) | oldest_due_strict | 0.316 | 0.315 | 0.213 | +0.103 |
| S4, mod4 (64 partitions) | oldest_due_nonstrict | 0.361 | 0.362 | 0.150 | +0.211 |
| S4, all (256) | keys_strict | 0.410 | 0.392 | 0.293 | +0.117 |
| S4, all (256) | keys_nonstrict | 0.406 | 0.413 | 0.318 | +0.088 |
| S4, all (256) | oldest_created | 0.318 | 0.318 | 0.221 | +0.097 |
| S4, all (256) | oldest_due_strict | 0.351 | 0.346 | 0.308 | +0.043 |
| S4, all (256) | oldest_due_nonstrict | 0.316 | 0.312 | 0.285 | +0.031 |
| S5, mod4 | keys_strict | 0.256 | 0.271 | 0.186 | +0.070 |
| S5, mod4 | keys_nonstrict | 0.261 | 0.277 | 0.192 | +0.069 |
| S5, mod4 | oldest_created | 0.209 | 0.216 | 0.136 | +0.073 |
| S5, mod4 | oldest_due_strict | 0.272 | 0.297 | 0.205 | +0.067 |
| S5, mod4 | oldest_due_nonstrict | 0.286 | 0.307 | 0.141 | +0.145 |


**Optimizer trace range analysis** (`trace/<query>.json`, summary `results/c/iso/trace-summary.txt`, identical for all five queries): with the 4th index the optimizer evaluates 3 range alternatives instead of 2. The two alternatives on i1 and i3 are single ranges (`(NEW,NULL)`); the 4th index `idx_outbox_record_status_partition_created` contributes one range per owned partition, **64 ranges (mod4) or 256 ranges (all)**, each an index dive (estimate "clipped up"; rows estimate 507,690 / 67,072 on S4, 38,179 on S5), so 66 / 258 ranges against 2 without it. The chosen access path is the same in both schemas.

**Verdict: planning.**

- The EXPLAIN-only difference (+0.07..0.21 ms with 64 owned partitions, +0.02..0.12 ms with 256) matches the bench difference (S8-S4 `keys_strict` +0.12 ms with +0.14 ms EXPLAIN; `oldest_due_nonstrict` +0.23 ms with +0.21 ms EXPLAIN; S4 `keys_strict` +0.08 ms with +0.12 ms EXPLAIN). The relative effect is largest on the shortest queries (`oldest_*` up to +115 %), the absolute effect is the same for all five.
- Execution is unchanged: identical access paths and rows examined (`handlers.csv`).
- The cause is the range estimation over the extra index for the IN list of owned partitions (64 or 256 index dives) on each planning of the statement. S16 (16 owned partitions) shows a smaller bench penalty (+0.04..0.07 ms), S8 (64) +0.12 ms.
- Not noise: `c-1` and `c-1b` agree within 0.03 ms on every cell, the differences are 2-8 times that (exception: S4 `oldest_due_nonstrict`, +0.03 ms, within the drift).
- Size: 0.03-0.24 ms per statement; the scheduler issues each of these queries about once per iteration. Not removable without hints, other index shapes or server settings (out of scope).

## 10. Harness changes

One addition, documented in `harness/README.md` ("MariaDB port", "Confirm stage"): `bench plantime` (`sql/.../Plantime.kt`, command registered in `Main.kt`; the harness was rebuilt once with `:sql:installDist` before pass 1). Nothing else changed: `run-cells.sh`, `lane.sh` and `bench` are as before. The worktree is unchanged.

## 11. Deferred-test candidates

1. S9 pass 2, and for `c-2` a churn run without the 2700 s cap (or with fewer retention batches) to quantify the retention cost.
2. A cheaper `count_partitions` plan at full ownership (the 14-40 % loss against `c-ref`): another index shape or width of the 4th index; hint-free only.
3. Repository-level run (`bin/repo-bench`) and the integration tests with the final DDL and forms (the overlays must become the branch code); MySQL 9 portability of the forms (`NOT EXISTS`, ordered subqueries).
4. Reduce the planning cost of the 4th index (64 / 256 range dives): hypothesis that fewer, wider ranges or a different leading column order lowers it; test on a screen cell before any server-setting change (out of scope here).
5. Validation stage: solo reruns of S4, S5 and S10 for the chosen candidate after the container restart.

## 12. Files

`experiments/c/{run-c.sh,run-equiv.sh,summarize.py,plans.py,c-2/ddl.sql}`, `harness/sql/src/main/kotlin/bench/Plantime.kt`, `results/c/{pass1,pass2,equiv,iso}/`, `results/c/logs/`, `results/c/driver.txt`, `results/c/iso/trace-summary.txt`.
