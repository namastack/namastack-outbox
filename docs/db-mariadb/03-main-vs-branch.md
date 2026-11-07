# 03 — `main` vs branch on MariaDB (phase 3, run `cmp`)

Run `cmp`: `mdb-main` `f4446c8c` vs `mdb-opt` `2a2663de`, MariaDB 12.3.3, lane 1 only, two passes in reversed order with a container
restart, rerun of the pass-1 cells measured under outside load (load 11-15) on a quiet host (load 1-2). Raw data and every table:
`results/cmp/SUMMARY.md` (sections cited as §n). Noise thresholds: `STATE.md`. Numbers are medians in ms, "p1/p2" = pass 1 / pass 2;
change = branch vs main on the mean of both passes. Equivalence passes everywhere (§6), so all differences below are performance only.

## 1. Verdict

The branch is a large win where it was designed to be (partial ownership, 16-64 partitions, and S2-sized backlogs), but it has
**severe P1 regressions at full ownership (256 partitions) whenever the NEW backlog is large**: `keys_strict` goes from milliseconds
to 0.8-60+ s on S4, S5, S6, S7, and a single poller owning all partitions collapses under unthrottled producers (S10-S2-p1-unthr:
~2 rec/s vs ~340-390 on main). One index, B-1d `idx_outbox_record_status_completed_partition (status, completed_at, partition_no)`,
causes most of them through a statistics-based misestimate (section 3). Several P2 queries also regress at partial ownership.
The branch cannot be adopted as is on MariaDB; round 1 must fix the problem cells in section 5 first.

## 2. Results per query (P1, then P2), partial ownership first

### P1 HOT (`keys_strict`, `keys_nonstrict`, `pages`)

| Label | keys_strict main → opt | keys_nonstrict main → opt | pages main → opt |
|---|---|---|---|
| S8-S2 | 0.96 → 0.78 (−19 %) | 1.65 → 0.79 (−52 %) | 0.64 → 0.50 (−22 %) |
| S8-S4 | 1.15 → 0.88 (−23 %) | **26 188 → 0.96** | 0.54 → 0.54 (no diff.) |
| S8-S5 | **15 134 → 197** | 2 436 → 8.9 | 4 945 → 1 475 (−70 %) |
| S8-S6 | 4.8 → 0.72 (−85 %) | 3.2 → 0.77 (−76 %) | 0.37 → 0.32 (−15 %) |
| S8-S7 | 0.56 → 0.46 (−18 %) | 5.9 → 0.55 (−91 %) | 0.36 → 0.32 (no diff.) |
| S16-S2 | 0.83 → 0.81 (no diff.) | 0.52 → 0.83 (**+60 %**, < 0.4 ms abs.) | 0.50 → 0.43 (−14 %) |
| S16-S4 | 0.53 → 0.43 (−19 %) | **5 870 → 0.47** | 0.32 → 0.32 (no diff.) |
| S1 | 1.29 → 0.82 (−36 %) | 1.51 → 0.82 (−46 %) | 0.53 → 0.43 (−18 %) |
| S2 | 1.62 → 0.80 (−50 %) | 4.56 → 0.87 (−81 %) | 0.51 → 0.41 (−20 %) |
| S4 | 2.1 → **over 60 s** | 105 877* → 108 746* (both bad) | 0.44 → not measured ¹ |
| S4-b100 | 2.1 → **over 60 s** | 96 225* → 115 018* (both bad) | 1.4 → not measured ¹ |
| S5 | 4.7 → **15 494** | 13 861 → 16 924 (+22 %) | 4 457 → 1 476 (−67 %) |
| S6 | 401 → **15 895** | 389 → **15 696** | 0.36 → 0.31 (−13 %) |
| S7 | 1.24 → **789** | 816 → 802 (no diff.) | 0.35 → 0.32 (no diff.) |
| S9 (p1 only ²) | 1.23 → 0.72 (−42 %) | 3.21 → 0.68 (−79 %) | 0.36 → 0.36 (no diff.) |

¹ `pages` gets its keys from `keys_strict`; when `keys_strict` stops at the limit the harness records `pages` as "stopped at limit
(keys_strict)" without running it. Not a `pages` regression; `pages` on S4 must be re-measured with keys from a working key query.
² S9 is not like for like: main's retention could not keep up (section 4.3), its table is ~60 % larger after churn.

### P1 WRITE

No WRITE difference above the noise threshold (60 % and >= 0.2 ms) in any bench label. Committed write cost: section 4.

### P2 MON

| Label | count_statuses ³ | count_partitions | oldest_created | oldest_due_strict | oldest_due_nonstrict |
|---|---|---|---|---|---|
| S8-S2 | 3.6 → 4.7 (+31 %) | 0.50 → 0.50 (no diff.) | 0.96 → 0.70 (−28 %) | 2.2 → 0.51 (−77 %) | 0.59 → 0.87 (**+46 %**) |
| S8-S4 | 176 → 220 (+25 %) | 62 → 197 (**+220 %**) | 14 633 → 297 (−98 %) | 22 173 → 0.60 | 91 → 280 (**+208 %**) |
| S8-S5 | 36 → 45 (+25 %) | 8.7 → 39 (**+350 %**) | 2 401 → 58 (−98 %) | 2 596 → 1.1 | 12.8 → 57 (**+343 %**) |
| S8-S6 | 18 → 22 (+27 %) | 6.6 → 21 (**+221 %**) | 4 049 → 34 (−99 %) | 4.3 → 0.42 (−90 %) | 0.54 → 1.19 (**+118 %**) |
| S8-S7 | 49 → 69 (+42 %) | 0.57 → 1.15 (**+100 %**) | 3.1 → 1.9 (−39 %) | 7.9 → 0.31 (−96 %) | 0.79 → 1.92 (**+144 %**) |
| S16-S2 | 2.8 → 3.6 (+29 %) | 0.19 → 0.34 (+78 %) | 0.28 → 0.28 (no diff.) | 0.58 → 0.63 (no diff.) | 0.23 → 0.34 (+44 %) |
| S16-S4 | 86 → 108 (+25 %) | 7.5 → 94 (**+1 152 %**) | 5 535 → 5 214 (both bad) | 7 957 → 0.30 | 11.5 → **5 052** |
| S2 | 2.8 → 3.6 (+32 %) | 1.9 → 0.59 (−70 %) | 2.0 → 0.74 (−64 %) | 5.3 → 0.54 (−90 %) | 1.4 → 0.91 (−36 %) |
| S4 | 89 → 161 (+80 %) | 421 → 167 (−60 %) | 93 933* → 83 026* (both bad) | 25 749 → **over 61 s** | 89 746* → 79 843* (both bad) |
| S5 | 17.4 → 21.9 (+25 %) | 13 201 → 22 | 3 027 → **14 888** | 3 576 → **14 853** | 38 → **14 137** |
| S6 | 17.7 → 22.5 (+27 %) | 24.7 → 22.8 (no diff.) | 7 294 → **16 567** | 413 → **16 522** | 1.8 → **16 233** |
| S7 | 49 → 70 (+42 %) | 804 → 1.46 | 808 → 784 (no diff.) | 840 → 804 (no diff.) | 2.7 → **771** |

³ main = `count_new` + `count_failed` (two statements); branch = one grouped `count_statuses` over NEW and FAILED. Main also issues
`count_completed` (90-200 ms on 1M rows) on every refresh, which the branch drops by default, so B-5 is a net P2 win even though the
grouped statement itself is 25-42 % slower than the two single counts.

`count_partition_single` (deprecated `namastack-outbox-metrics` module, not a target per `01-usage-map.md`) regresses everywhere
(0.1-1 ms → 1-170 ms) because the branch has no `partition_no`-leading index. Reported only.

## 3. Plans: why the branch regresses

**R1 — `idx_..._status_completed_partition` (B-1d) wins the cost model at 256 partitions with a false estimate.** With all 256
partitions in the `IN` list, MariaDB is above `eq_range_index_dive_limit` (200) and estimates the ranges from index statistics
instead of index dives. For the prefix `(status, completed_at, partition_no)` the statistics say about one row per value (COMPLETED rows
make `completed_at` nearly unique), so the estimate is **256 rows** for `status = 'NEW' AND completed_at IS NULL AND partition_no IN
(256 values)`. The real count is the whole NEW backlog. The optimizer then prefers B-1d + filesort over the ordered, early-stopping
scan of B-1a:

```
S5 keys_strict, opt:  o: range key=idx_outbox_record_status_completed_partition parts=[status,completed_at,partition_no]
                         est=256 r_rows=199980 examined=199980 covering=no   filesort: yes
                      earlier: ref key=idx_outbox_record_key_completed_created_id loops=199960 examined=321899
                      -> 521 879 rows examined for 10 returned (main: 20 020)
S7 keys_strict, opt:  same index, est=256, r_rows=10000, 10 000 NOT EXISTS probes, filesort  (main: 20 rows examined)
S4 keys_strict, opt:  same index, est=256, ~1M NEW rows (not finished in 60 s)               (main: 20 rows examined)
```

Every query with `status = NEW AND completed_at IS NULL AND partition_no IN (...)` picks it at 256 partitions: `keys_strict`,
`keys_nonstrict`, `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` on S4/S5/S6/S7 (all `est=256`). B-1d also does not
cover `next_retry_at`, `created_at` or `record_key`, so every examined row is a primary-key lookup. At 16-64 partitions the dives are
exact and the optimizer picks the right index (S8/S16 wins above); on S2 the NEW backlog is only 1 200 rows, so the mistake is cheap.

**R2 — S10 single-poller collapse (S10-S2-p1-unthr).** Same mechanism in production form: one poller owns 256 partitions while
producers add ~2 100 rec/s. The NEW backlog grows, every `keys_strict` call scans and sorts it through B-1d (p95 40-41 s in both
passes), throughput falls to 1.9-2.3 rec/s, and the long reads hold the purge back (history list 142-159 k vs 1.4-2.2 k on main).
With 4 pollers (64 partitions each, below the dive limit) the branch beats main (1 325-1 400 vs 969-981 rec/s).

**R3 — `oldest_due_nonstrict` and `count_partitions` at partial ownership.** With exact estimates the optimizer still picks B-1d,
non-covering, for `MIN(next_retry_at)`: S16-S4 examines 62 536 rows with a PK lookup each (5 s), while main's
`(partition_no, status, next_retry_at)` reads the same rows index-only (11.5 ms). `count_partitions` (no `completed_at` predicate in
the SQL) reads a `status` range and filters `partition_no` inside the index instead of seeking per partition (S16-S4 7.5 → 94 ms).
`MIN` cannot stop early on any branch index, because `partition_no` comes after the MIN column or the filter column.

**R4 — unsolved in both variants: S4 `keys_nonstrict`, `oldest_created`, `oldest_due_nonstrict` (80-120 s), S16-S4 `oldest_created`
(5 s).** Main reads the whole 1 M NEW backlog through `status_retry`/`partition_status_retry` with a temporary table or per-partition range; the branch
reads them through B-1d. Neither has an ordered-scan-with-early-stop plan. Exploration should target these too (they are where the
`ORDER BY ... LIMIT 1` forms of M2/M3 and user ideas E4/E5 apply).

Plans of every regression and of every query > 5 ms, per label: `results/cmp/SUMMARY.md` §2.

## 4. Write cost, size, churn, concurrency

### 4.1 Write cost (§3)

| | S2-wc (small backlog) | S4-wc (1M NEW backlog) |
|---|---|---|
| insert redo/write | −3 % (no diff.) | **+120 %** (2.6 → 5.7 KB), pages/write +76 % |
| insert 4 threads | no diff. | redo +27 %, p50 +15 % |
| upd_complete | redo −15 % | no diff.; p95 −32 % |
| upd_retry | no diff. | **redo +284 %** (3.7 → 14.3 KB), p50 +17 % |
| upd_fail | redo −14 % | p50 −11 % |
| delete_by_id | redo −31 % | p50 −14 % |

The S4-wc increase follows B-1d and B-1a: all NEW rows share `(status='NEW', completed_at=NULL)`, so inserts and retries land in
256 partition-ordered (B-1d) and created-ordered (B-1a) hot regions of a large index; B-1d grew from 119 MB to 169 MB (+41 %) during
the measured writes alone (main's largest growth: 8 %). Latencies stay within noise except the insert/retry p50 above.

### 4.2 Size (S2, before writes)

main: 6 indexes, secondary total 374 MB; branch: 5 indexes, 348 MB (−7 %). Branch: B-1a 114 MB, B-1b 95 MB, B-1c 71 MB, B-1d 67 MB.

### 4.3 S9 churn (pass 1 only, by user decision; §4)

Main's retention select takes ~2 min per 1 000-row batch on the churned table: it removed 28 000 rows in the 2 700 s cap (churn total
2 950 s), against 500 000 rows in 311 s on the branch. **This is a P3 query, but the effect is not P3**: with main the table grows
without bound under this load (1.30 M vs 0.83 M COMPLETED after churn). Post-churn P1 reads are faster on the branch (−42 % to −79 %);
`upd_complete`/`upd_fail` are slower (+80 %, +249 %) on the branch's churned indexes, measured against main's larger but less
fragmented table (not like for like; phase 6 repeats S9).

### 4.4 S10 (§5)

| Cell | main rec/s | branch rec/s | keys p50/p95 main → branch |
|---|---|---|---|
| S10-S2-p1 (500/s) | 514 | 513 | 1.3/1.6 → 0.6/0.8 ms |
| S10-S2-p4 (500/s) | 512 | 513 | 0.6/1.5 → 0.4/0.7 ms |
| **S10-S2-p1-unthr** | **343-394** | **1.9-2.3** | 7.5/24 ms → 1.1 ms / **40.6 s** |
| S10-S2-p4-unthr | 969-981 | 1 325-1 400 | 9.4/37 → 1.0/21 ms |
| S10-S4-p4 (500/s) | 701-768 | 776-922 | 11.2/47 → 10.3/40 ms |
| S10-S4-p4-unthr | 370-388 | 539-545 | 21/102 → 17/66 ms |

No lock waits, deadlocks or errors in any cell. The branch's history list ends higher in every S4 cell (31-47 k vs 12-18 k).

## 5. Problem cells for exploration (priority order)

1. **Full ownership, large NEW backlog, P1 HOT:** `keys_strict` on S4, S4-b100, S5, S6, S7; `keys_nonstrict` on S6; and
   **S10-S2-p1-unthr**. Cause R1/R2 (B-1d at 256 partitions). Must be fixed before anything else.
2. **Full ownership P2:** `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` on S5, S6, S7, S4 (R1).
3. **Partial ownership P2:** `oldest_due_nonstrict` (S8-*: +46 % to +343 %; S16-S4: 11.5 ms → 5 s) and `count_partitions`
   (S8-S4/S5/S6/S7, S16-S4: +100 % to +1 152 %) (R3).
4. **Unsolved in both:** S4 `keys_nonstrict`, `oldest_created`, `oldest_due_nonstrict`; S16-S4 `oldest_created` (R4).
5. **Write cost with a large backlog:** S4-wc insert and `upd_retry` redo (+120 %, +284 %).
6. Minor: `count_statuses` +25-42 % everywhere (statement form, see note 3); `keys_nonstrict` S16-S2 +60 % (< 0.4 ms absolute).
7. To re-measure: `pages` on S4/S4-b100 (not run, footnote 1).

Screening only on the S8 labels would miss items 1, 2 and 4: round 1 adds full-ownership labels (S5, S7, S6, S4) and
S10-S2-p1-unthr to the screen (see `experiments/round1-spec.md`).

## 6. Branch items (candidates for the M1 ablation)

| Item | Evidence | Assessment |
|---|---|---|
| B-1a i1 `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` | the access path of every partial-ownership win | keep (exact column order is round-1 material: E4/E5) |
| B-1b i2 `(record_key, completed_at, created_at, id)` | NOT EXISTS probes, `pages` | keep |
| B-1c i3 `(status, completed_at, next_retry_at, partition_no)` | used by `count_statuses`, `oldest_due_*` at partial ownership | test removal/replacement (M1, E2, E4, E5) |
| **B-1d i4 `(status, completed_at, partition_no)`** | cause of R1, R2, R3 and of the S4-wc growth | **harmful at 256 partitions; first ablation (`r1-m1d`) and replacement (`r1-e3` `(status, partition_no)`)** |
| B-2/B-3 key queries (`NOT EXISTS`) | large wins at partial ownership | keep; forms tested in M6 once R1 is removed |
| B-4 `pages` + `completed_at IS NULL` | −13 % to −70 % | keep |
| B-5 grouped `count_statuses` | statement +25-42 %, but saves `count_completed` | keep; split forms in pack r1-qf1/qf2 |
| B-6 `oldest_created` MIN + `completed_at IS NULL` | wins at S8, loses at 256 partitions (R1) | M2 (`ORDER BY ... LIMIT 1`) |
| B-7 `oldest_due_*` | strict: big win; nonstrict: regression (R3) | M3 |
| B-8 limits | retention keeps up under churn (section 4.3) | keep |

## 7. Equivalence

S1, S2, S5, S7: PASS on every compared query (find_pending_all / find_failed_all skipped as unbounded). One WARN on S5 `keys_nonstrict`:
the 10-key limited lists differ by one key at a `created_at` tie, the full key sets are equal (the tie rule of the harness).
