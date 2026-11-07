# `mdb-opt2` (`4037faa5`) vs `mdb-final` (`f31bc505`) on MariaDB 12.3.3

Brief `briefs/06-experiment.md`, spec `experiments/opt2-spec.md`, stage `screen` (one pass) plus S9, S10 and write cost. Driver `experiments/opt2-run.sh` (log `results/opt2/logs/driver.log`, PID in `results/opt2/driver.txt`), summarizer `experiments/opt2-summarize.py`, data `results/opt2/`.

## Hypothesis and setup

`opt2` is the moved branch head: DDL `(status, created_at, id, partition_no, next_retry_at, record_key)`, `(record_key, completed_at, created_at, id)`, `(status, next_retry_at, partition_no)`, `(status, partition_no)`. Question: does `mdb-final` (indexes with `completed_at` as second column of the status indexes, 4th index `(status, partition_no, created_at)`) beat it by the ADR-0005 rule?

- Worktrees: `mdb-opt2` `/home/azamojski/outbox-wt-mdb-opt2` (detached `4037faa5`, source untouched), `mdb-final` `/home/azamojski/outbox-wt-mdb-final` (`f31bc505`, catalog reused). Jars built with the JDK 25 init script, catalog `mdb-opt2` extracted (28 queries per dialect), `hashcheck` 0 mismatches of 20004 keys.
- MariaDB 12.3.3, `innodb_buffer_pool_size` = 134217728 (verified before the run). UUIDv4 only (`HARNESS_ID_VERSION` unset, `ids.txt` in each variant dir). Lane 1 solo for every timed cell (lane 2 only for equivalence, before timing). Per label `mdb-opt2` then `mdb-final` back to back on one snapshot. Per-query limit 60 s (none stopped). Single pass. Host load at cell start 0.8-3.3 (one start at 9.0, `S10-S4-p4` final, right after the preceding 4-poller cell; reruns rule threshold 18 never hit). Free disk 38-41 GB.
- Noise thresholds from `STATE.md` (HOT 11 %, MON 10 % / 0.04 ms, WRITE 60 % and 0.2 ms, redo/write 12 %, p50 8 %, p95 25 %, pages/write 64 %). The mdb-main column is the phase-6 mean of pass 1 and 2 (`results/final/SUMMARY.md`), reference only.

## Catalog and DDL differences (MariaDB)

DDL (`outbox-tables.sql`): `opt2` has `idx_outbox_record_status_created_id (status, created_at, id, partition_no, next_retry_at, record_key)`, `..._status_retry_partition (status, next_retry_at, partition_no)`, `..._status_partition (status, partition_no)`; `final` has `status_completed_created_id (status, completed_at, created_at, id, ...)`, `status_completed_retry (status, completed_at, next_retry_at, partition_no)`, `status_partition_created (status, partition_no, created_at)`. `key_completed_created_id` is equal. Both 5 indexes incl. primary.

Catalog: 23 of 28 statements identical (apart from the `-- source` line). The 5 that differ:

| id | `opt2` | `final` |
|---|---|---|
| `keys_strict`, `keys_nonstrict` | no outer `completed_at IS NULL` | outer `o.completed_at IS NULL` |
| `oldest_created` | `ORDER BY created_at LIMIT :limit`, no `completed_at` predicate | adds `AND completed_at IS NULL`, `ORDER BY created_at, id` |
| `oldest_due_nonstrict` | no `completed_at` predicate | adds `o.completed_at IS NULL` |
| `oldest_due_strict` | no outer `completed_at IS NULL` | outer `o.completed_at IS NULL` |

## Equivalence (lane 2, `HARNESS_SAMPLE_ORDERED=1`, before timing)

`opt2` against `final`: S1 PASS 28; S5 PASS 26, SKIP 2; S7 PASS 26, SKIP 2 (the two SKIPs are the write queries the harness does not compare). No WARN, FAIL or ERROR. `results/opt2/equiv/mariadb/`.

## 1. Reads (single pass, medians ms)

Partial ownership first (S8-S2, S8-S4, S8-S5, S16-S2, S16-S4), then S4, S5, S7, S9. "final vs opt2" uses the thresholds above; `(nd)` = no difference. Full tables in the appendix. Summary:

- **Every HOT/MON read except the ones below is "no difference".** `keys_strict`, `keys_nonstrict`, `pages`, `oldest_*`, `count_partition_single`, `count_new` and `count_partitions` are within the noise threshold in all labels (e.g. S8-S4 `keys_strict` 0.491 vs 0.501, `oldest_created` 0.261 vs 0.254, `oldest_due_nonstrict` 0.405 vs 0.379; S8-S5 `keys_strict` 49.5 vs 53.8 ms; S5 `pages` 1431 vs 1432 ms).
- Outside the threshold, small: `count_failed` +13 % (S8-S2), +14 % (S16-S2), +11 % (S9) and `count_statuses` +12 % (S16-S2), `count_partitions` S5 +10 %; `pages` S16-S2 -11 % (0.365 vs 0.324). All are MON statements (P3) on millisecond scale; the plans are the same shape (covering index range scans), `final` scans slightly larger index entries (`completed_at` in the key). Reported, not blocking.
- WRITE rolled-back statements: all within the 60 % threshold (`upd_fail` S4 0.162 vs 0.331 ms is below 0.2 ms absolute difference).
- **S9 `count_completed`: 503.5 ms (opt2) vs 83.6 ms (final)** is a table-state effect, not a plan effect: the opt2 retention phase was capped (below), so the table still held 1.38 M rows (final 0.77 M); both plans are a covering `status`-prefix index scan (`idx_outbox_record_status_partition` vs `idx_outbox_record_status_partition_created`, 1.31 M vs 0.83 M rows examined).
- Plans for all queries that exceed 5 ms or differ beyond the threshold are in `results/opt2/<variant>/mariadb/<label>/plans/*.summary.txt`; the heads of the plans agree between the two variants (`keys_strict` on S9: index `status_created_id` vs `status_completed_created_id`, 12 rows examined each, same dependent subquery on `key_completed_created_id`). All queries over 5 ms are the full-count MON statements and S5 `pages`/`keys_strict` (S5 hot-key shape), with the same row counts in both variants.
- Against `mdb-main` (phase-6 reference) `opt2` and `final` both remove the multi-second statements (S4 `keys_nonstrict` 84.9 s, `oldest_created` 79.6 s, `oldest_due_strict` 19.8 s on main; sub-ms on both candidates). S16-S2 and S8-S2 are at main level or better.

## 2. Write cost (S2-wc and S4-wc, committed single-row phases)

Redo bytes/write / pages/write / p50 ms / p95 ms / throughput per second (single pass; the writecost noise of 12 % redo is typical 1-4 %).


### S2-wc: per phase redo B/write / pages/write / p50 ms / p95 ms / throughput per s

| run | insert_1thread | insert_4threads | upd_complete_committed | upd_retry_committed | upd_fail_committed | delete_by_id_committed |
|---|---|---|---|---|---|---|
| mdb-opt2 | 2331 / 1.35 / 0.73 / 0.98 / 856 | 2254 / 1.27 / 1.28 / 2.01 / 2854 | 10609 / 4.50 / 0.80 / 1.13 / 126 | 3225 / 1.84 / 0.70 / 0.88 / 149 | 4131 / 2.97 / 0.74 / 0.96 / 148 | 7630 / 6.15 / 0.59 / 0.71 / 151 |
| mdb-final | 2346 / 1.25 / 0.71 / 0.91 / 1351 | 2330 / 1.27 / 1.24 / 1.99 / 2911 | 8511 / 3.73 / 0.73 / 1.11 / 144 | 2764 / 2.35 / 0.65 / 0.81 / 150 | 4005 / 3.01 / 0.66 / 0.94 / 149 | 5723 / 5.33 / 0.57 / 0.61 / 152 |
| mdb-main (ph6 p1) | 2484 / 1.37 / 0.67 / 0.84 / 1422 | 2311 / 1.30 / 1.15 / 1.83 / 3160 | 5694 / 2.80 / 0.68 / 0.96 / 109 | 2895 / 3.40 / 0.60 / 0.76 / 151 | 4630 / 3.50 / 0.61 / 0.75 / 150 | 2816 / 5.11 / 0.54 / 0.70 / 152 |

final vs opt2 (redo 12 %, p50 8 %, p95 25 % thresholds):

- insert_1thread: redo +1% (nd); p50 -3% (nd); p95 -7% (nd); pages/write -7% (nd)
- insert_4threads: redo +3% (nd); p50 -3% (nd); p95 -1% (nd); pages/write +0% (nd)
- upd_complete_committed: redo -20%; p50 -9%; p95 -2% (nd); pages/write -17% (nd)
- upd_retry_committed: redo -14%; p50 -7% (nd); p95 -8% (nd); pages/write +28% (nd)
- upd_fail_committed: redo -3% (nd); p50 -10%; p95 -3% (nd); pages/write +1% (nd)
- delete_by_id_committed: redo -25%; p50 -4% (nd); p95 -13% (nd); pages/write -13% (nd)

Index sizes MB before -> after, count, all-phase redo:

- mdb-opt2: PRIMARY 1362->1374, key_completed_created_id 95->97, status_created_id 109->109, status_partition 62->66, status_retry_partition 66->67, total 332->339 MB, count 5, all-phase redo 31 MB
- mdb-final: PRIMARY 1362->1374, key_completed_created_id 95->97, status_completed_created_id 114->115, status_completed_retry 71->72, status_partition_created 66->70, total 347->355 MB, count 5, all-phase redo 30 MB
- mdb-main (ph6 p1): PRIMARY 1362->1374, partition_status_retry 66->70, record_key_completed_created 95->97, record_key_created 90->90, status_created 62->63, status_retry 62->63, total 374->383 MB, count 6, all-phase redo 29 MB

### S4-wc: per phase redo B/write / pages/write / p50 ms / p95 ms / throughput per s

| run | insert_1thread | insert_4threads | upd_complete_committed | upd_retry_committed | upd_fail_committed | delete_by_id_committed |
|---|---|---|---|---|---|---|
| mdb-opt2 | 5672 / 2.54 / 0.74 / 1.00 / 1260 | 3168 / 2.17 / 1.32 / 2.51 / 2547 | 14903 / 6.59 / 0.92 / 1.56 / 544 | 14484 / 3.47 / 0.78 / 1.05 / 485 | 4126 / 1.62 / 0.64 / 0.84 / 601 | 2114 / 1.00 / 0.61 / 0.76 / 812 |
| mdb-final | 2647 / 1.43 / 0.69 / 0.88 / 1376 | 2418 / 1.33 / 1.18 / 1.92 / 3037 | 14612 / 8.04 / 0.98 / 1.92 / 422 | 14322 / 3.48 / 0.79 / 1.03 / 553 | 4093 / 2.87 / 0.72 / 0.97 / 567 | 2119 / 0.97 / 0.61 / 0.76 / 814 |
| mdb-main (ph6 p1) | 2598 / 1.47 / 0.69 / 0.91 / 1371 | 2436 / 1.33 / 1.17 / 1.95 / 3057 | 14346 / 7.39 / 0.97 / 1.62 / 470 | 3604 / 1.78 / 0.67 / 0.86 / 592 | 3874 / 3.01 / 0.71 / 0.95 / 647 | 1679 / 2.37 / 0.70 / 0.86 / 755 |

final vs opt2 (redo 12 %, p50 8 %, p95 25 % thresholds):

- insert_1thread: redo -53%; p50 -7% (nd); p95 -13% (nd); pages/write -44% (nd)
- insert_4threads: redo -24%; p50 -11%; p95 -23% (nd); pages/write -39% (nd)
- upd_complete_committed: redo -2% (nd); p50 +6% (nd); p95 +23% (nd); pages/write +22% (nd)
- upd_retry_committed: redo -1% (nd); p50 +1% (nd); p95 -1% (nd); pages/write +0% (nd)
- upd_fail_committed: redo -1% (nd); p50 +14%; p95 +15% (nd); pages/write +77%
- delete_by_id_committed: redo +0% (nd); p50 +0% (nd); p95 -1% (nd); pages/write -3% (nd)

Index sizes MB before -> after, count, all-phase redo:

- mdb-opt2: PRIMARY 2656->2669, key_completed_created_id 181->198, status_created_id 206->224, status_partition 113->164, status_retry_partition 122->123, total 622->709 MB, count 5, all-phase redo 222 MB
- mdb-final: PRIMARY 2656->2669, key_completed_created_id 181->195, status_completed_created_id 212->229, status_completed_retry 127->129, status_partition_created 122->130, total 642->683 MB, count 5, all-phase redo 201 MB
- mdb-main (ph6 p1): PRIMARY 2656->2669, partition_status_retry 122->131, record_key_completed_created 181->196, record_key_created 175->176, status_created 113->114, status_retry 113->114, total 704->731 MB, count 6, all-phase redo 143 MB

Reading: on S2-wc `final` writes **less redo** than `opt2` for `upd_complete` (-20 %), `upd_retry` (-14 %) and `delete_by_id` (-25 %), and `insert`/`upd_fail` are equal; p50 differences are 3-10 %, p95 within noise. On S4-wc the only large differences are `insert_1thread` redo (`opt2` 5672 vs `final` 2647 B/write; `opt2` index `status_partition` grew 113->164 MB during the cell, `final` `status_partition_created` 122->130 MB) and `insert_4threads` (-24 % redo); the rest is equal within noise (`upd_fail` pages/write +77 %, supporting evidence only). Index total: `opt2` 332 MB (S2) / 622 MB (S4) vs `final` 347 / 642 MB (+4 % / +3 %), `main` 374 / 704 MB; all-phase redo S2 31 vs 30 MB, S4 222 vs 201 MB (`main` 143 MB). The `opt2` S4 insert redo is plausibly the key order of `(status, partition_no)` (inserts land at random positions in the NEW range, page splits) against `(status, partition_no, created_at)` where `created_at` makes the new entries append in time order; one pass only, so treat as a signal.

## 3. S9 retention (churn on S2 data, `--retention-max-s 2700`)


| run | removed_by_retention | capped at s | churn s | table_rows | data MB | index MB | file MB | HLL after | redo MB |
|---|---|---|---|---|---|---|---|---|---|
| mdb-opt2 | 18000 | 2700 | 2917 | 1087785->1384421 | 1362->1737 | 332->529 | 1699->2324 | 0 | 2492 |
| mdb-final | 500000 | no | 358 | 844682->765838 | 1362->1747 | 347->443 | 1715->2315 | 0 | 3222 |
| mdb-main (ph6) | 31000 | 2700 | 2890 | 877645->1196664 | 1362->1722 | 374->556 | 1741->2366 | 1 | 2509 |
| mdb-opt (ph6) | 500000 | no | 303 | 902367->700444 | 1362->1751 | 348->421 | 1715->2315 | 0 | 3247 |

**`opt2` cannot run retention at scale.** The retention select `ids_completed_before` (`status = 'COMPLETED' AND completed_at < ...`, `ORDER BY completed_at LIMIT 1000`) has no index on `(status, completed_at, ...)` in the `opt2` DDL; it runs as a sort over the COMPLETED rows ("Creating sort index" seen in the process list, about 2 min per 1,000-row batch). It reached the 2700 s cap after deleting 18,000 of 500,000 rows (like `mdb-main`: 31,000), whereas `final` removed all 500,000 in 358 s (`mdb-opt` phase 6: 303 s, which had `status_completed_*` indexes). The `final` index `(status, completed_at, ...)` is the one that serves this P2 query.

## 4. Concurrency (S10, 60 s, single pass)


| label | run | processed/s | inserted/s | keys p50 | pages p50 | upd p50 | redo MB | HLL end |
|---|---|---|---|---|---|---|---|---|
| S10-S2-p1-unthr | mdb-opt2 | 371.9 | 2293.8 | 0.58 | 5.84 | 1.28 | 466 | 971 |
| S10-S2-p1-unthr | mdb-final | 305.1 | 1692.4 | 0.58 | 5.93 | 1.27 | 337 | 203 |
| S10-S2-p1-unthr | mdb-main | 358.6 | 2324.9 | 7.35 | 0.21 | 1.18 | 458 | 647 |
| S10-S2-p1-unthr | mdb-main p2 | 387.8 | 2517.4 | 7.39 | 0.21 | 1.18 | 498 | 505 |
| S10-S2-p4-unthr | mdb-opt2 | 1043.2 | 2056.7 | 2.32 | 0.97 | 1.48 | 585 | 2438 |
| S10-S2-p4-unthr | mdb-final | 1085.9 | 1792.1 | 0.86 | 2.76 | 1.29 | 531 | 1373 |
| S10-S2-p4-unthr | mdb-main | 1062.9 | 2364.2 | 9.25 | 0.25 | 1.22 | 634 | 1472 |
| S10-S2-p4-unthr | mdb-main p2 | 934.5 | 2069.7 | 8.89 | 0.24 | 1.22 | 552 | 9 |
| S10-S4-p4 | mdb-opt2 | 780.8 | 443.6 | 10.33 | 0.83 | 2.08 | 575 | 27578 |
| S10-S4-p4 | mdb-final | 907.8 | 497.7 | 9.63 | 0.84 | 1.56 | 498 | 28068 |
| S10-S4-p4 | mdb-main | 768.9 | 498.0 | 10.52 | 4.68 | 1.76 | 517 | 16426 |
| S10-S4-p4 | mdb-main p2 | 784.1 | 497.9 | 10.48 | 4.58 | 1.60 | 487 | 18403 |

`S10-S2-p1-unthr`: `final` processed 305/s and inserted 1692/s against `opt2` 372/s and 2294/s, but this label is the least reliable one on this host (phase 6 `final` 402-465/s, 2263-2621 inserted/s, `main` 359-388/s; the unthrottled inserter and the poller compete for the same CPU and both vary by 20 % pass to pass); both are far from the `opt` collapse seen in phase 6 (2.3/s). `S10-S2-p4-unthr` processed 1043 vs 1086/s (no difference), keys p50 2.32 vs 0.86 ms (final faster; phase 6 `final` 0.97). `S10-S4-p4` (throttled, rate 500): processed 781 (`opt2`, inserted 444/s, below the 500/s target) vs 908 (`final`, 498/s); `opt2` update p50 2.08 vs 1.56 ms. HLL end values are 27-28 k for both (same as phase 6). The `S10-S4-p4` final cell started at load 9 (tail of the preceding cell) but gave the same value as phase 6 (888-918/s).

## 5. Verdict: `mdb-final` against `mdb-opt2`

**Win for `mdb-final`** by the ADR-0005 rule:
- P1 reads (`keys_*`, `oldest_*`, `pages`): equal to `opt2` within noise on every label, all sub-ms to a few ms on partial ownership; `opt2`'s catalog form without `completed_at IS NULL` does not cost or gain anything measurable here.
- P2 (retention, S9): `final` 358 s for 500k rows vs `opt2` 18k rows in 2700 s (capped). This is the decisive difference and needs the `(status, completed_at, ...)` index prefix.
- Write cost: `final` equal or lower redo (S2 `upd_complete` -20 %, `delete_by_id` -25 %), indexes +3-4 % in size, same index count (5 incl. primary).
- P3 regressions: `count_failed`/`count_statuses` +11-14 % on S8-S2/S16-S2/S9 (ms scale), reported not blocking.
- S10: no systematic difference except the noisy `S10-S2-p1-unthr` (see above) and `final` better on `S10-S4-p4`.

What to apply to the branch (`opt2`, `4037faa5`): replace `(status, created_at, id, ...)` by `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, `(status, next_retry_at, partition_no)` by `(status, completed_at, next_retry_at, partition_no)` and `(status, partition_no)` by `(status, partition_no, created_at)`, with the five `final` statement forms in the table above (the `completed_at IS NULL` predicates; `oldest_created` with `ORDER BY created_at, id`).

## Deviations and harness changes

No harness code change. New files: `experiments/opt2-run.sh`, `experiments/opt2-summarize.py`, worktree `/home/azamojski/outbox-wt-mdb-opt2`, catalog `harness/catalog/mdb-opt2/`, `harness/build/variants/mdb-opt2.jars`. One pass only (screen stage), so single-pass differences below about 2x threshold are not conclusive; the S9 retention cap and the S2 write-cost redo differences are well above noise. Label order in the run was grouped by scenario (S8-S2, S16-S2, S2-wc, S9, S10-S2, then the S4 labels, S4-wc, S10-S4-p4, then S8-S5, S5, S7) so each snapshot was generated once. S5 and S7 reads (`mdb-opt2` S5 `pages` 1431 ms) were all below the 60 s limit.

## Appendix: read tables (all labels)

### S8-S2

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.470 | 0.473 | +1% (nd) | 0.595 |  |  |
| keys_nonstrict | 0.492 | 0.508 | +3% (nd) | 0.989 |  |  |
| pages | 0.309 | 0.312 | +1% (nd) | 0.387 |  |  |
| insert | 0.377 | 0.440 | +17% (nd) | 0.600 |  |  |
| upd_complete | 0.299 | 0.364 | +22% (nd) | 0.454 |  |  |
| upd_retry | 0.166 | 0.258 | +55% (nd) | 0.284 |  |  |
| upd_fail | 0.182 | 0.205 | +13% (nd) | 0.290 |  |  |
| delete_by_id | 0.328 | 0.350 | +7% (nd) | 0.400 |  |  |
| count_statuses | 2.352 | 2.583 | +10% (nd) | - |  |  |
| count_partitions | 0.281 | 0.293 | +4% (nd) | 0.291 |  |  |
| oldest_created | 0.230 | 0.209 | -9% (nd) | 0.569 |  |  |
| oldest_due_strict | 0.328 | 0.311 | -5% (nd) | 1.260 |  |  |
| oldest_due_nonstrict | 0.317 | 0.307 | -3% (nd) | 0.348 |  |  |
| count_new | 0.209 | 0.231 | +11% (nd) | 0.194 |  |  |
| count_failed | 1.717 | 1.937 | +13% | 1.680 |  |  |
| count_completed | 95.5 | 103.7 | +9% (nd) | 92.5 |  |  |
| count_partition_single | 0.101 | 0.092 | -9% (nd) | 0.099 |  |  |

### S8-S4

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.491 | 0.501 | +2% (nd) | 0.709 |  |  |
| keys_nonstrict | 0.535 | 0.549 | +3% (nd) | 21920.8 |  |  |
| pages | 0.333 | 0.323 | -3% (nd) | 0.322 |  |  |
| insert | 0.466 | 0.472 | +1% (nd) | 0.637 |  |  |
| upd_complete | 0.794 | 0.891 | +12% (nd) | 0.785 |  |  |
| upd_retry | 0.414 | 0.428 | +3% (nd) | 0.395 |  |  |
| upd_fail | 0.248 | 0.328 | +32% (nd) | 0.286 |  |  |
| delete_by_id | 0.409 | 0.489 | +20% (nd) | 0.485 |  |  |
| count_statuses | 100.4 | 110.0 | +10% (nd) | - |  |  |
| count_partitions | 27.7 | 29.9 | +8% (nd) | 30.0 |  |  |
| oldest_created | 0.261 | 0.254 | -3% (nd) | 14550.8 |  |  |
| oldest_due_strict | 0.369 | 0.359 | -3% (nd) | 19017.6 |  |  |
| oldest_due_nonstrict | 0.405 | 0.379 | -6% (nd) | 45.6 |  |  |
| count_new | 85.9 | 92.8 | +8% (nd) | 85.1 |  |  |
| count_failed | 0.080 | 0.085 | +6% (nd) | 0.074 |  |  |
| count_completed | 94.2 | 100.1 | +6% (nd) | 92.1 |  |  |
| count_partition_single | 0.458 | 0.470 | +3% (nd) | 0.479 |  |  |

### S8-S5

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 49.5 | 53.8 | +9% (nd) | 13253.9 |  |  |
| keys_nonstrict | 0.574 | 0.605 | +5% (nd) | 2178.6 |  |  |
| pages | 1269.2 | 1271.7 | +0% (nd) | 4021.2 |  |  |
| insert | 0.420 | 0.434 | +3% (nd) | 0.507 |  |  |
| upd_complete | 0.384 | 0.403 | +5% (nd) | 0.582 |  |  |
| upd_retry | 0.167 | 0.240 | +44% (nd) | 0.283 |  |  |
| upd_fail | 0.170 | 0.246 | +45% (nd) | 0.249 |  |  |
| delete_by_id | 0.253 | 0.244 | -4% (nd) | 0.450 |  |  |
| count_statuses | 21.5 | 21.5 | +0% (nd) | - |  |  |
| count_partitions | 4.137 | 4.340 | +5% (nd) | 4.290 |  |  |
| oldest_created | 0.214 | 0.204 | -5% (nd) | 2320.8 |  |  |
| oldest_due_strict | 0.576 | 0.559 | -3% (nd) | 2418.7 |  |  |
| oldest_due_nonstrict | 0.329 | 0.312 | -5% (nd) | 6.399 |  |  |
| count_new | 20.0 | 18.8 | -6% (nd) | 17.3 |  |  |
| count_failed | 0.093 | 0.080 | -14% (nd) | 0.089 |  |  |
| count_completed | 30.8 | 31.0 | +1% (nd) | 28.1 |  |  |
| count_partition_single | 0.278 | 0.281 | +1% (nd) | 0.305 |  |  |

### S16-S2

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.620 | 0.647 | +4% (nd) | 0.635 |  |  |
| keys_nonstrict | 0.663 | 0.701 | +6% (nd) | 0.406 |  |  |
| pages | 0.365 | 0.324 | -11% | 0.385 |  |  |
| insert | 0.471 | 0.472 | +0% (nd) | 0.655 |  |  |
| upd_complete | 0.358 | 0.340 | -5% (nd) | 0.439 |  |  |
| upd_retry | 0.257 | 0.261 | +2% (nd) | 0.278 |  |  |
| upd_fail | 0.257 | 0.275 | +7% (nd) | 0.267 |  |  |
| delete_by_id | 0.314 | 0.318 | +1% (nd) | 0.404 |  |  |
| count_statuses | 2.324 | 2.604 | +12% | - |  |  |
| count_partitions | 0.154 | 0.140 | -9% (nd) | 0.137 |  |  |
| oldest_created | 0.175 | 0.194 | +11% (nd) | 0.205 |  |  |
| oldest_due_strict | 0.494 | 0.484 | -2% (nd) | 0.426 |  |  |
| oldest_due_nonstrict | 0.203 | 0.199 | -2% (nd) | 0.175 |  |  |
| count_new | 0.204 | 0.216 | +6% (nd) | 0.196 |  |  |
| count_failed | 1.718 | 1.958 | +14% | 1.655 |  |  |
| count_completed | 95.3 | 103.5 | +9% (nd) | 90.9 |  |  |
| count_partition_single | 0.099 | 0.087 | -12% (nd) | 0.088 |  |  |

### S16-S4

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.420 | 0.448 | +7% (nd) | 0.565 |  |  |
| keys_nonstrict | 0.473 | 0.479 | +1% (nd) | 5114.0 |  |  |
| pages | 0.319 | 0.328 | +3% (nd) | 0.331 |  |  |
| insert | 0.503 | 0.501 | -0% (nd) | 0.680 |  |  |
| upd_complete | 0.797 | 0.839 | +5% (nd) | 0.758 |  |  |
| upd_retry | 0.423 | 0.428 | +1% (nd) | 0.342 |  |  |
| upd_fail | 0.166 | 0.257 | +55% (nd) | 0.285 |  |  |
| delete_by_id | 0.408 | 0.467 | +14% (nd) | 0.472 |  |  |
| count_statuses | 99.3 | 106.2 | +7% (nd) | - |  |  |
| count_partitions | 6.825 | 7.335 | +7% (nd) | 7.402 |  |  |
| oldest_created | 0.172 | 0.191 | +11% (nd) | 4841.3 |  |  |
| oldest_due_strict | 0.279 | 0.295 | +6% (nd) | 7307.9 |  |  |
| oldest_due_nonstrict | 0.205 | 0.228 | +11% (nd) | 11.3 |  |  |
| count_new | 85.1 | 93.0 | +9% (nd) | 85.8 |  |  |
| count_failed | 0.079 | 0.093 | +18% (nd) | 0.079 |  |  |
| count_completed | 92.5 | 100.1 | +8% (nd) | 92.4 |  |  |
| count_partition_single | 0.450 | 0.490 | +9% (nd) | 0.497 |  |  |

### S4

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.674 | 0.679 | +1% (nd) | 1.623 |  |  |
| keys_nonstrict | 0.670 | 0.692 | +3% (nd) | 84918.9 |  |  |
| pages | 0.329 | 0.338 | +3% (nd) | 0.350 |  |  |
| insert | 0.508 | 0.510 | +0% (nd) | 0.574 |  |  |
| upd_complete | 0.766 | 0.836 | +9% (nd) | 0.740 |  |  |
| upd_retry | 0.399 | 0.422 | +6% (nd) | 0.395 |  |  |
| upd_fail | 0.162 | 0.331 | +104% (nd) | 0.249 |  |  |
| delete_by_id | 0.369 | 0.415 | +12% (nd) | 0.572 |  |  |
| count_statuses | 99.4 | 106.3 | +7% (nd) | - |  |  |
| count_partitions | 110.8 | 118.7 | +7% (nd) | 357.9 |  |  |
| oldest_created | 0.347 | 0.358 | +3% (nd) | 79556.0 |  |  |
| oldest_due_strict | 0.417 | 0.428 | +3% (nd) | 19786.0 |  |  |
| oldest_due_nonstrict | 0.366 | 0.366 | +0% (nd) | 37868.7 |  |  |
| count_new | 84.9 | 92.9 | +10% (nd) | 85.3 |  |  |
| count_failed | 0.090 | 0.093 | +3% (nd) | 0.080 |  |  |
| count_completed | 93.2 | 99.9 | +7% (nd) | 92.7 |  |  |
| count_partition_single | 0.453 | 0.488 | +8% (nd) | 0.499 |  |  |

### S5

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.621 | 0.628 | +1% (nd) | 4.695 |  |  |
| keys_nonstrict | 0.727 | 0.690 | -5% (nd) | 13471.7 |  |  |
| pages | 1430.9 | 1431.8 | +0% (nd) | 4270.3 |  |  |
| insert | 0.383 | 0.467 | +22% (nd) | 0.450 |  |  |
| upd_complete | 0.610 | 0.669 | +10% (nd) | 0.776 |  |  |
| upd_retry | 0.342 | 0.349 | +2% (nd) | 0.341 |  |  |
| upd_fail | 0.250 | 0.259 | +4% (nd) | 0.272 |  |  |
| delete_by_id | 0.249 | 0.242 | -3% (nd) | 0.571 |  |  |
| count_statuses | 21.5 | 21.7 | +1% (nd) | - |  |  |
| count_partitions | 25.2 | 27.7 | +10% | 13484.7 |  |  |
| oldest_created | 0.281 | 0.274 | -2% (nd) | 2909.7 |  |  |
| oldest_due_strict | 0.660 | 0.662 | +0% (nd) | 3426.2 |  |  |
| oldest_due_nonstrict | 0.413 | 0.415 | +0% (nd) | 37.3 |  |  |
| count_new | 18.6 | 19.2 | +3% (nd) | 17.2 |  |  |
| count_failed | 0.093 | 0.095 | +2% (nd) | 0.084 |  |  |
| count_completed | 29.4 | 30.2 | +3% (nd) | 27.9 |  |  |
| count_partition_single | 0.281 | 0.286 | +2% (nd) | 0.293 |  |  |

### S7

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.593 | 0.606 | +2% (nd) | 1.243 |  |  |
| keys_nonstrict | 0.620 | 0.613 | -1% (nd) | 749.0 |  |  |
| pages | 0.323 | 0.309 | -4% (nd) | 0.355 |  |  |
| insert | 0.352 | 0.394 | +12% (nd) | 0.483 |  |  |
| upd_complete | 0.492 | 0.468 | -5% (nd) | 0.721 |  |  |
| upd_retry | 0.233 | 0.239 | +3% (nd) | 0.301 |  |  |
| upd_fail | 0.203 | 0.276 | +36% (nd) | 0.274 |  |  |
| delete_by_id | 0.418 | 0.337 | -19% (nd) | 0.483 |  |  |
| count_statuses | 65.6 | 68.8 | +5% (nd) | - |  |  |
| count_partitions | 1.613 | 1.735 | +8% (nd) | 794.0 |  |  |
| oldest_created | 0.290 | 0.302 | +4% (nd) | 785.3 |  |  |
| oldest_due_strict | 0.387 | 0.403 | +4% (nd) | 815.5 |  |  |
| oldest_due_nonstrict | 0.398 | 0.418 | +5% (nd) | 2.689 |  |  |
| count_new | 1.024 | 1.049 | +2% (nd) | 0.963 |  |  |
| count_failed | 51.3 | 54.1 | +5% (nd) | 47.6 |  |  |
| count_completed | 56.0 | 60.5 | +8% (nd) | 56.1 |  |  |
| count_partition_single | 0.097 | 0.099 | +2% (nd) | 0.096 |  |  |

### S9

| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |
|---|---|---|---|---|---|---|
| keys_strict | 0.599 | 0.630 | +5% (nd) | 1.246 |  |  |
| keys_nonstrict | 0.606 | 0.610 | +1% (nd) | 3.155 |  |  |
| pages | 0.318 | 0.309 | -3% (nd) | 0.389 |  |  |
| insert | 0.492 | 0.363 | -26% (nd) | 0.408 |  |  |
| upd_complete | 0.509 | 0.425 | -17% (nd) | 0.378 |  |  |
| upd_retry | 0.225 | 0.147 | -35% (nd) | 0.226 |  |  |
| upd_fail | 0.314 | 0.291 | -7% (nd) | 0.247 |  |  |
| delete_by_id | 0.704 | 0.613 | -13% (nd) | 0.705 |  |  |
| count_statuses | 3.887 | 4.057 | +4% (nd) | - |  |  |
| count_partitions | 0.432 | 0.439 | +2% (nd) | 1.508 |  |  |
| oldest_created | 0.277 | 0.297 | +7% (nd) | 1.507 |  |  |
| oldest_due_strict | 0.396 | 0.407 | +3% (nd) | 3.809 |  |  |
| oldest_due_nonstrict | 0.395 | 0.418 | +6% (nd) | 1.032 |  |  |
| count_new | 0.213 | 0.207 | -3% (nd) | 0.186 |  |  |
| count_failed | 2.916 | 3.225 | +11% | 2.927 |  |  |
| count_completed | 503.5 | 83.6 | -83% | 123.3 |  |  |
| count_partition_single | 0.089 | 0.082 | -8% (nd) | 0.102 |  |  |
