# Round 1b — query forms on the round-1a winner (screen)

MariaDB 12.3.3, lane 1 only (lane 2 idle during every timed step), one pass per cell, stage `screen`. Base `mdb-opt` `2a2663de2f2028790180399293981f7eca587ad1` (worktree clean, never modified). Spec `experiments/round1b-spec.md`. Raw data `results/r1b/<run>/mariadb/<label>/` (timings.csv, plans/, handlers.csv, env.txt, writecost.csv, concurrency-*.txt), equivalence `results/r1b/equiv/mariadb/{S1,S5,S7}/`, logs `results/r1b/logs/`. `results/r1b/failures.txt` does not exist: no failed step, no step stopped by a per-query limit, no ERROR cell. Host load at every step start 0.7-2.5 (rule: rerun only if > 18; nothing rerun). Drivers `experiments/r1b/run-r1b.sh` (parts 1-2), `run-m5.sh` (part 3).

## 0. Setup

- `b-base` = `r1-m1d` DDL (i1 `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, i2 `(record_key, completed_at, created_at, id)`, i3 `(status, completed_at, next_retry_at, partition_no)`) + branch SQL. DDLs `experiments/r1b/<run>/ddl.sql` (all applied on 12.3.3 before timing). `b-e5l`: `idx_outbox_record_status_created_id_partition_retry_key (status, created_at, id, partition_no, next_retry_at, record_key)`, i2, `idx_outbox_record_status_retry_partition (status, next_retry_at, partition_no)` + pack `r1-qf14` (the five E5 overlays, copied to `experiments/r1b/b-e5l/queries/`). `b-m4`: i1, `idx_outbox_record_key_completed_created (record_key, completed_at, created_at)` (implicit PK suffix), i3.
- Packs `b-qf1`..`b-qf9`, `b-m1q`: `experiments/r1b/<pack>/queries/<query id>.sql`, copies of the `Q-merged` forms of the spec table, run on the `b-base` schema, `--catalog-variant mdb-opt`, `--only` the overlaid ids, no re-prepare between packs.
- Order per scenario block (gen once): `b-base` (prepare), `b-e5l`, `b-m4` (own prepare each), then a second prepare of the `b-base` DDL on which the 10 packs and, last, `b-base2` (drift check) ran. Packs are therefore compared with the mean of `b-base` and `b-base2` (first prepare / second prepare); they agree within the HOT/MON threshold everywhere (largest: S8-S5 `pages` 1280 / 1228).
- Labels S8-S4, S16-S4, S4, S8-S5, S5, S6, S8-S7, S7; limits 10 s (S4, S5, S6, S7, S8-S4, S16-S4) and max(2 x `b-base` median, 60 s) on S8-S5 / S8-S7; `pages` on S4 with `--pages-keys-file experiments/r1/s4-pages-keys.txt`. Writecost S2-wc, S4-wc and S10-S2-p1-unthr for the four DDL runs. Groups HOT,WRITE,MON.
- Thresholds (STATE.md): HOT 11 %, MON 10 % (< 0.3 ms: 0.04 ms), WRITE 60 % and >= 0.2 ms; redo bytes/write 12 %.
- `mdb-main` values: phase 3 (`results/cmp/`, rerun values where they exist, else pass 1 / pass 2); `count_statuses` = `count_new` + `count_failed`.
- Form ids in tables: oc-00-main (main SQL, equals oc-07) / odn-00-main (equals odn-05) are the same text as the no-`completed_at` MIN forms; they appear once.

## 1. Equivalence (mdb-opt vs run, `HARNESS_SAMPLE_ORDERED=1`)

All 10 packs and `b-e5l` on S1 (28 verdicts each) and S5 (26 each): **0 FAIL, 0 ERROR**.
**WARN: `kn-08-groupby` (pack b-qf8) and `kn-09-groupby-minid` (b-qf9), `keys_nonstrict` on S5.** The limited key lists differ (1 key at the boundary), full key sets equal (100); both sorted by MIN(created_at) and the created_at sequences identical, so it is a permutation inside created_at ties (10 keys tie at the limit). These two forms change the key order contract (ties) and are not candidates anyway (12-76x slower than the base on S5). All other verdicts PASS (S5 SKIP: `find_pending_all`, `find_failed_all`, S1/S3 only). `pa-05-setstmt-nomerge` (SET STATEMENT prefix) ran through the driver without error (PASS).
S7 equivalence for the survivors (`b-qf1`, `b-qf5`, `b-qf6`, `b-e5l`): see section 7.
The E5 invariant (`status = NEW` <=> `completed_at IS NULL`) is assumed, S1/S5/S7 do not contain rows that violate it.

## 2. Expected candidate: `b-base` + `oc-03-order-id` + `odn-01-order` (pack `b-qf1`)

`oldest_created`: `... ORDER BY created_at, id LIMIT :limit`; `oldest_due_nonstrict`: `... ORDER BY next_retry_at LIMIT :limit` (both keep `completed_at IS NULL`, so no dependence on the E5 invariant). Median ms; plans from `results/r1b/b-qf1/mariadb/<label>/plans/`. Both plans are index-only ordered scans that stop at the first owned row (`sql_select_limit: 1`, no filesort, no temporary table): `oldest_created` = `ref` on i1 `(status, completed_at)` prefix, `oldest_due_nonstrict` = `range` on i3. Rows examined = rows read until the first owned partition (1 when the head row is owned; 3-15 at partial ownership).

| label | query | main | b-base (mean of 2) | candidate (b-qf1) | handler rows read (next+key) | access / key / examined |
|---|---|---|---|---|---|---|
| S8-S4 | oldest_created | 15214 / 14052 | 165.8 | 0.17 | 8 | ref / idx_outbox_record_status_completed_created_id / 8 |
| S8-S4 | oldest_due_nonstrict | 90.86 / 90.55 | 142.7 | 0.23 | 8 | range / idx_outbox_record_status_completed_retry / 8 |
| S16-S4 | oldest_created | 4827 | 145.9 | 0.15 | 12 | ref / idx_outbox_record_status_completed_created_id / 12 |
| S16-S4 | oldest_due_nonstrict | 11.29 | 122.7 | 0.18 | 15 | range / idx_outbox_record_status_completed_retry / 15 |
| S4 | oldest_created | 79279 | 202.7 | 0.28 | 1 | ref / idx_outbox_record_status_completed_created_id / 1 |
| S4 | oldest_due_nonstrict | 75281 | 203.5 | 0.40 | 1 | range / idx_outbox_record_status_completed_retry / 1 |
| S8-S5 | oldest_created | 2440 / 2361 | 31.95 | 0.15 | 7 | ref / idx_outbox_record_status_completed_created_id / 7 |
| S8-S5 | oldest_due_nonstrict | 12.82 / 12.83 | 29.91 | 0.21 | 3 | range / idx_outbox_record_status_completed_retry / 3 |
| S5 | oldest_created | 3229 / 2825 | 39.90 | 0.29 | 1 | ref / idx_outbox_record_status_completed_created_id / 1 |
| S5 | oldest_due_nonstrict | 38.01 / 37.30 | 42.06 | 0.35 | 1 | range / idx_outbox_record_status_completed_retry / 1 |
| S6 | oldest_created | 7516 / 7072 | 40.58 | 0.29 | 1 | ref / idx_outbox_record_status_completed_created_id / 1 |
| S6 | oldest_due_nonstrict | 1.77 / 1.76 | 1.42 | 0.38 | 1 | range / idx_outbox_record_status_completed_retry / 1 |
| S8-S7 | oldest_created | 3.11 | 1.78 | 0.16 | 3 | ref / idx_outbox_record_status_completed_created_id / 3 |
| S8-S7 | oldest_due_nonstrict | 0.77 | 1.76 | 0.22 | 3 | range / idx_outbox_record_status_completed_retry / 3 |
| S7 | oldest_created | 883.6 | 2.31 | 0.28 | 1 | ref / idx_outbox_record_status_completed_created_id / 1 |
| S7 | oldest_due_nonstrict | 2.72 | 2.58 | 0.34 | 1 | range / idx_outbox_record_status_completed_retry / 1 |

**Result: confirmed.** 0.15-0.40 ms at every label against 1.4-203 ms for `b-base` (MIN forms scan the NEW range) and against 0.8 ms - 79 s for `mdb-main`. At or below `mdb-main` at all labels (S6 `oldest_due_nonstrict` 0.38 vs 1.77, S8-S7 0.22 vs 0.77). The remaining sub-0.4 ms values differ from each other by less than the 0.04 ms MON noise floor, i.e. no difference between the candidate and the `oc-06`/`oc-02`/`odn-02` variants. Equivalence PASS (S1, S5; S7 in section 7). Contract: `oldest_created` / `oldest_due_nonstrict` return zero rows (not a NULL row) when nothing is due; the repository must map that to "none", as it did for the `ORDER BY ... LIMIT` form of `a06a74c0`. Write cost: none (no index change).

## 3. Part 1 — DDL runs (HOT / MON medians, ms)

Partial ownership first. `mdb-main` = phase 3.


#### S8-S4

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 1.10 / 1.19 | 0.41 | 0.40 | 0.41 | 0.41 |
| keys_nonstrict | 27557 / 24818 | 0.44 | 0.44 | 0.43 | 0.42 |
| pages | 0.54 / 0.53 | 0.32 | 0.33 | 0.33 | 0.32 |
| count_statuses | 175.8 / 176.8 | 117.0 | 110.7 | 118.9 | 118.7 |
| count_partitions | 61.28 / 62.06 | 114.5 | 104.7 | 115.2 | 114.9 |
| oldest_created | 15214 / 14052 | 162.7 | 0.15 | 166.2 | 168.9 |
| oldest_due_strict | 21952 / 22394 | 0.25 | 0.25 | 0.28 | 0.27 |
| oldest_due_nonstrict | 90.86 / 90.55 | 142.4 | 0.18 | 143.9 | 142.9 |

#### S16-S4

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 0.54 | 0.39 | 0.38 | 0.40 | 0.40 |
| keys_nonstrict | 5112 | 0.40 | 0.40 | 0.41 | 0.41 |
| pages | 0.32 | 0.33 | 0.32 | 0.33 | 0.33 |
| count_statuses | 85.00 | 117.2 | 107.6 | 118.8 | 118.5 |
| count_partitions | 7.46 | 105.8 | 97.50 | 107.7 | 107.5 |
| oldest_created | 4827 | 145.2 | 0.11 | 146.4 | 146.5 |
| oldest_due_strict | 7376 | 0.24 | 0.23 | 0.24 | 0.24 |
| oldest_due_nonstrict | 11.29 | 122.2 | 0.15 | 122.7 | 123.1 |

#### S8-S5

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 14919 / 15350 | 108.3 | 103.1 | 107.6 | 106.7 |
| keys_nonstrict | 2441 / 2431 | 4.77 | 4.72 | 4.81 | 4.82 |
| pages | 5080 / 4810 | 1280 | 1286 | 1276 | 1228 |
| count_statuses | 35.73 / 35.77 | 24.44 | 21.45 | 23.40 | 23.45 |
| count_partitions | 8.68 / 8.80 | 24.47 | 22.25 | 24.18 | 24.41 |
| oldest_created | 2440 / 2361 | 32.81 | 0.14 | 31.11 | 31.09 |
| oldest_due_strict | 2606 / 2586 | 0.51 | 0.49 | 0.50 | 0.50 |
| oldest_due_nonstrict | 12.82 / 12.83 | 30.85 | 0.18 | 29.14 | 28.96 |

#### S8-S7

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 0.55 | 0.40 | 0.39 | 0.41 | 0.41 |
| keys_nonstrict | 5.85 | 0.43 | 0.41 | 0.43 | 0.43 |
| pages | 0.35 | 0.31 | 0.32 | 0.32 | 0.32 |
| count_statuses | 48.68 | 69.01 | 65.24 | 68.76 | 68.52 |
| count_partitions | 0.57 | 1.44 | 1.32 | 1.42 | 1.44 |
| oldest_created | 3.11 | 1.78 | 0.15 | 1.78 | 1.77 |
| oldest_due_strict | 7.79 | 0.25 | 0.24 | 0.25 | 0.23 |
| oldest_due_nonstrict | 0.77 | 1.76 | 0.18 | 1.78 | 1.76 |

#### S4

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 1.58 | 0.60 | 0.59 | 0.60 | 0.61 |
| keys_nonstrict | 85037 | 0.59 | 0.59 | 0.59 | 0.60 |
| pages | 0.33 | 0.32 | 0.33 | 0.33 | 0.34 |
| count_statuses | 85.30 | 117.0 | 108.9 | 118.5 | 118.8 |
| count_partitions | 358.5 | 128.8 | 120.6 | 129.8 | 130.3 |
| oldest_created | 79279 | 202.1 | 0.24 | 206.7 | 203.4 |
| oldest_due_strict | 19974 | 0.37 | 0.35 | 0.36 | 0.35 |
| oldest_due_nonstrict | 75281 | 202.2 | 0.33 | 204.9 | 204.9 |

#### S5

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 4.69 / 4.67 | 3.35 | 3.29 | 3.38 | 3.40 |
| keys_nonstrict | 14408 / 13314 | 2.68 | 2.73 | 2.73 | 2.69 |
| pages | 4648 / 4265 | 1582 | 1573 | 1577 | 1555 |
| count_statuses | 17.35 / 17.54 | 23.39 | 21.38 | 23.41 | 23.41 |
| count_partitions | 13281 / 13122 | 27.19 | 25.09 | 27.27 | 27.32 |
| oldest_created | 3229 / 2825 | 39.88 | 0.24 | 39.68 | 39.92 |
| oldest_due_strict | 3724 / 3428 | 0.63 | 0.63 | 0.60 | 0.62 |
| oldest_due_nonstrict | 38.01 / 37.30 | 41.80 | 0.33 | 42.25 | 42.32 |

#### S6

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 417.5 / 385.0 | 0.78 | 0.76 | 0.77 | 0.78 |
| keys_nonstrict | 405.1 / 372.5 | 0.77 | 0.74 | 0.76 | 0.77 |
| pages | 0.36 / 0.35 | 0.32 | 0.31 | 0.31 | 0.30 |
| count_statuses | 17.74 / 17.69 | 23.54 | 25.33 | 23.43 | 23.41 |
| count_partitions | 24.66 / 24.79 | 27.10 | 30.68 | 27.11 | 27.18 |
| oldest_created | 7516 / 7072 | 40.75 | 0.24 | 40.38 | 40.41 |
| oldest_due_strict | 442.6 / 383.3 | 0.40 | 0.40 | 0.40 | 0.38 |
| oldest_due_nonstrict | 1.77 / 1.76 | 1.42 | 0.34 | 1.44 | 1.42 |

#### S7

| query | mdb-main | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|---|
| keys_strict | 1.21 | 0.59 | 0.59 | 0.59 | 0.59 |
| keys_nonstrict | 758.0 | 0.58 | 0.61 | 0.60 | 0.61 |
| pages | 0.33 | 0.32 | 0.31 | 0.33 | 0.33 |
| count_statuses | 48.18 | 68.84 | 64.92 | 68.88 | 68.34 |
| count_partitions | 890.4 | 1.68 | 1.55 | 1.70 | 1.72 |
| oldest_created | 883.6 | 2.30 | 0.25 | 2.31 | 2.32 |
| oldest_due_strict | 914.7 | 0.36 | 0.36 | 0.36 | 0.37 |
| oldest_due_nonstrict | 2.72 | 2.53 | 0.32 | 2.58 | 2.63 |

### Write cost and S10 (solo cells; single run each)

| metric | b-base | b-e5l | b-m4 | b-base2 |
|---|---|---|---|---|
| S2-wc insert 1 thread p50 / p95 ms | 0.652 / 0.797 | 0.649 / 0.797 | 0.659 / 0.813 | 0.656 / 0.813 |
| S2-wc insert redo B/write | 2233 | 2178 | 2286 | 2223 |
| S2-wc upd_complete redo B/write | 4358 | 4856 (+11 %) | 4412 | 5175 (+19 %) |
| S2-wc upd_retry / upd_fail / delete redo B/write | 3050 / 2969 / 2005 | 3310 / 3022 / 2083 | 2847 / 3080 / 1767 | 3178 / 2973 / 1961 |
| S4-wc insert p50 ms / redo B/write | 0.674 / 2371 | 0.673 / 2334 | 0.675 / 2379 | 0.713 / 2410 |
| S4-wc upd_complete / upd_retry / upd_fail / delete redo B/write | 13713 / 14245 / 3986 / 1723 | 13822 / 14527 / 3882 / 1719 | 13931 / 14179 / 3923 / 1714 | 13905 / 14297 / 3952 / 1707 |
| S4-wc 4-thread insert throughput /s | 3080 | 3104 | 3071 | 3090 |
| index count (incl. PK) | 4 | 4 | 4 | 4 |
| indexes total MB (S2 / S4 data) | 267.8 / 495.9 | 257.7 / 484.9 | 267.8 / 495.9 | 267.8 / 495.9 |
| S10-S2-p1-unthr processed rec/s (keys p50 / p95 ms) | 429 (0.57 / 2.69) | 420 (0.57 / 3.55) | 444 (0.57 / 2.65) | 423 (0.57 / 2.24) |

(S2-wc `upd_complete` redo is 4358 -> 5175 between `b-base` and `b-base2` of the same DDL, so the +11 % of `b-e5l` is inside the run-to-run spread; redo threshold 12 %.) WRITE group bench cells: only isolated cells above the WRITE threshold (`b-e5l` S6 `upd_retry` 0.28 -> 0.80, S8-S7 `upd_retry` 0.27 -> 0.54, S7 `upd_complete` 0.30 -> 0.52; `b-m4` S8-S7 `upd_fail` 0.17 -> 0.42), none repeated across labels: noise of rolled-back single statements, not a signal.

### Verdicts

- **`b-e5l` (user idea E5 lean): no difference** to `b-base` + candidate. HOT identical; `oldest_*` 0.11-0.35 ms with its five overlays (same level as the candidate, differences < 0.04 ms); `count_statuses` / `count_partitions` 5-9 % lower (inside the 10 % MON threshold). Write cost at base level (S2-wc and S4-wc redo equal within noise, throughput equal, index bytes -3.7 %). It needs no `(status, partition_no)` index, so the 2x S2 redo of round 1a does not appear. Its price: the overlays are `status-conditional` (rely on `completed_at IS NULL <=> status IN (NEW, FAILED)`, not enforced by the schema), and the base gains nothing from it. Not carried to confirm as a separate DDL; it remains the fallback if the invariant is accepted.
- **`b-m4` (index `(record_key, completed_at, created_at)`): no difference.** Identical per-index size (the implicit PK suffix makes it the same B-tree), same plans and timings within noise at every label, same write cost. The explicit `id` is harmless; nothing to gain.

## 4. Part 2 — query packs on the `b-base` schema (median ms)

Each cell: form median (ratio against the mean of `b-base` / `b-base2`). Partial-ownership labels first. "main" is the phase 3 value. Forms are sorted by median. `oc-07-no-completed-min/oc-00-main` is the MIN text without `completed_at` (equal to main's SQL).

#### S8-S4 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 1.10 / 1.19 | 0.41 | 0.41 | ks-03-leftjoin 0.38 (0.93x); ks-02-tuple 0.39 (0.95x); ks-04-leftjoin-straight 0.39 (0.95x); ks-05-head 0.41 (1.00x); ks-01-split 0.46 (1.11x); ks-07-window-part 1129 (2746.09x); ks-00-main 1150 (2797.21x); ks-06-window 3714 (9037.47x) |
| keys_nonstrict | 27557 / 24818 | 0.44 | 0.42 | kn-03-leftjoin 0.42 (0.97x); kn-05-head 0.43 (1.00x); kn-04-leftjoin-straight 0.43 (1.00x); kn-02-tuple 0.44 (1.02x); kn-01-split 0.90 (2.07x); kn-07-window-part 822.8 (1906.93x); kn-00-main 1549 (3589.94x); kn-08-groupby 1560 (3615.68x); kn-09-groupby-minid 1617 (3747.94x); kn-06-window 3284 (7611.48x) |
| pages | 0.54 / 0.53 | 0.32 | 0.32 | pa-05-setstmt-nomerge 0.33 (1.02x); pa-00-main 0.34 (1.08x); pa-01-latejoin 0.37 (1.16x); pa-02-semi-in 0.51 (1.59x) |
| count_statuses | 175.8 / 176.8 | 117.0 | 118.7 | cs-06-incomplete 125.5 (1.06x); cs-02-split-null-sum 126.7 (1.08x); cs-03-split-null-count 434.5 (3.69x) |
| count_partitions | 61.28 / 62.06 | 114.5 | 114.9 | cp-04-incomplete 120.1 (1.05x); cp-02-split-null 120.7 (1.05x); cp-06-group-sum 152.9 (1.33x) |
| oldest_created | 15214 / 14052 | 162.7 | 168.9 | oc-06-order-limit 0.17 (<0.01x); oc-03-order-id 0.17 (<0.01x); oc-02-min-of-limit 0.20 (<0.01x); oc-07-no-completed-min/oc-00-main 161.4 (0.97x) |
| oldest_due_strict | 21952 / 22394 | 0.25 | 0.27 | ods-02-split 0.29 (1.13x); ods-05-leftjoin-straight 0.34 (1.33x); ods-04-leftjoin 0.36 (1.39x); ods-06-head 0.39 (1.49x); ods-08-min-of-limit 0.40 (1.53x); ods-01-min 998.8 (3856.51x); ods-00-main 1004 (3877.64x); ods-03-split-min 1359 (5246.00x); ods-07-window 3464 (13375.72x) |
| oldest_due_nonstrict | 90.86 / 90.55 | 142.4 | 142.9 | odn-01-order 0.23 (<0.01x); odn-02-min-of-limit 0.25 (<0.01x); odn-05-no-completed-min/odn-00-main 132.2 (0.93x) |

#### S16-S4 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 0.54 | 0.39 | 0.40 | ks-03-leftjoin 0.37 (0.93x); ks-02-tuple 0.37 (0.94x); ks-05-head 0.38 (0.97x); ks-04-leftjoin-straight 0.38 (0.97x); ks-01-split 0.42 (1.08x); ks-07-window-part 694.2 (1761.84x); ks-00-main 703.6 (1785.73x); ks-06-window 3623 (9196.24x) |
| keys_nonstrict | 5112 | 0.40 | 0.41 | kn-04-leftjoin-straight 0.39 (0.96x); kn-03-leftjoin 0.40 (0.97x); kn-05-head 0.41 (1.00x); kn-02-tuple 0.41 (1.01x); kn-01-split 0.83 (2.05x); kn-07-window-part 327.9 (808.57x); kn-00-main 464.0 (1144.26x); kn-08-groupby 467.8 (1153.75x); kn-09-groupby-minid 504.3 (1243.54x); kn-06-window 3249 (8013.15x) |
| pages | 0.32 | 0.33 | 0.33 | pa-00-main 0.33 (1.01x); pa-05-setstmt-nomerge 0.35 (1.09x); pa-01-latejoin 0.36 (1.11x); pa-02-semi-in 0.53 (1.62x) |
| count_statuses | 85.00 | 117.2 | 118.5 | cs-06-incomplete 125.4 (1.06x); cs-02-split-null-sum 126.5 (1.07x); cs-03-split-null-count 434.5 (3.69x) |
| count_partitions | 7.46 | 105.8 | 107.5 | cp-04-incomplete 109.5 (1.03x); cp-02-split-null 111.3 (1.04x); cp-06-group-sum 134.4 (1.26x) |
| oldest_created | 4827 | 145.2 | 146.5 | oc-06-order-limit 0.15 (<0.01x); oc-03-order-id 0.15 (<0.01x); oc-02-min-of-limit 0.17 (<0.01x); oc-07-no-completed-min/oc-00-main 142.2 (0.98x) |
| oldest_due_strict | 7376 | 0.24 | 0.24 | ods-02-split 0.27 (1.10x); ods-05-leftjoin-straight 0.32 (1.31x); ods-06-head 0.32 (1.31x); ods-04-leftjoin 0.35 (1.43x); ods-08-min-of-limit 0.35 (1.45x); ods-01-min 623.2 (2559.18x); ods-00-main 680.2 (2793.35x); ods-03-split-min 705.6 (2897.92x); ods-07-window 3441 (14130.40x) |
| oldest_due_nonstrict | 11.29 | 122.2 | 123.1 | odn-01-order 0.18 (<0.01x); odn-02-min-of-limit 0.20 (<0.01x); odn-05-no-completed-min/odn-00-main 112.0 (0.91x) |

#### S8-S5 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 14919 / 15350 | 108.3 | 106.7 | ks-05-head 53.29 (0.50x); ks-04-leftjoin-straight 94.70 (0.88x); ks-03-leftjoin 94.76 (0.88x); ks-01-split 105.8 (0.98x); ks-02-tuple 106.7 (0.99x); ks-00-main 117.0 (1.09x); ks-07-window-part 131.4 (1.22x); ks-06-window 508.1 (4.73x) |
| keys_nonstrict | 2441 / 2431 | 4.77 | 4.82 | kn-05-head 0.52 (0.11x); kn-01-split 4.44 (0.93x); kn-00-main 56.02 (11.69x); kn-08-groupby 58.44 (12.20x); kn-09-groupby-minid 61.05 (12.74x); kn-07-window-part 104.5 (21.81x); kn-06-window 464.9 (97.03x); kn-03-leftjoin 1337 (278.98x); kn-04-leftjoin-straight 1341 (279.80x); kn-02-tuple 1376 (287.27x) |
| pages | 5080 / 4810 | 1280 | 1228 | pa-05-setstmt-nomerge 1223 (0.98x); pa-01-latejoin 1301 (1.04x); pa-02-semi-in 1337 (1.07x); pa-00-main 4386 (3.50x) |
| count_statuses | 35.73 / 35.77 | 24.44 | 23.45 | cs-06-incomplete 24.98 (1.04x); cs-02-split-null-sum 25.05 (1.05x); cs-03-split-null-count 54.25 (2.27x) |
| count_partitions | 8.68 / 8.80 | 24.47 | 24.41 | cp-04-incomplete 25.01 (1.02x); cp-02-split-null 25.34 (1.04x); cp-06-group-sum 31.30 (1.28x) |
| oldest_created | 2440 / 2361 | 32.81 | 31.09 | oc-03-order-id 0.15 (<0.01x); oc-06-order-limit 0.17 (<0.01x); oc-02-min-of-limit 0.18 (<0.01x); oc-07-no-completed-min/oc-00-main 30.38 (0.95x) |
| oldest_due_strict | 2606 / 2586 | 0.51 | 0.50 | ods-06-head 0.33 (0.67x); ods-02-split 0.55 (1.09x); ods-04-leftjoin 0.60 (1.20x); ods-05-leftjoin-straight 0.61 (1.22x); ods-08-min-of-limit 0.66 (1.32x); ods-03-split-min 100.0 (199.29x); ods-00-main 100.7 (200.62x); ods-01-min 102.0 (203.25x); ods-07-window 485.3 (966.80x) |
| oldest_due_nonstrict | 12.82 / 12.83 | 30.85 | 28.96 | odn-01-order 0.21 (<0.01x); odn-02-min-of-limit 0.23 (<0.01x); odn-05-no-completed-min/odn-00-main 27.03 (0.90x) |

#### S8-S7 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 0.55 | 0.40 | 0.41 | ks-04-leftjoin-straight 0.38 (0.93x); ks-03-leftjoin 0.39 (0.96x); ks-02-tuple 0.39 (0.97x); ks-05-head 0.39 (0.98x); ks-01-split 0.44 (1.09x); ks-00-main 7.86 (19.46x); ks-07-window-part 348.3 (862.13x); ks-06-window 1207 (2987.35x) |
| keys_nonstrict | 5.85 | 0.43 | 0.43 | kn-04-leftjoin-straight 0.41 (0.94x); kn-03-leftjoin 0.42 (0.97x); kn-05-head 0.42 (0.98x); kn-02-tuple 0.43 (1.01x); kn-01-split 0.50 (1.16x); kn-00-main 4.59 (10.68x); kn-08-groupby 4.74 (11.02x); kn-07-window-part 4.81 (11.19x); kn-09-groupby-minid 5.42 (12.60x); kn-06-window 22.85 (53.15x) |
| pages | 0.35 | 0.31 | 0.32 | pa-01-latejoin 0.34 (1.07x); pa-05-setstmt-nomerge 0.34 (1.08x); pa-00-main 0.36 (1.14x); pa-02-semi-in 0.52 (1.64x) |
| count_statuses | 48.68 | 69.01 | 68.52 | cs-06-incomplete 71.36 (1.04x); cs-02-split-null-sum 71.78 (1.04x); cs-03-split-null-count 207.8 (3.02x) |
| count_partitions | 0.57 | 1.44 | 1.44 | cp-04-incomplete 1.49 (1.03x); cp-02-split-null 1.57 (1.09x); cp-06-group-sum 1.93 (1.34x) |
| oldest_created | 3.11 | 1.78 | 1.77 | oc-06-order-limit 0.15 (0.09x); oc-03-order-id 0.16 (0.09x); oc-02-min-of-limit 0.20 (0.11x); oc-07-no-completed-min/oc-00-main 1.79 (1.01x) |
| oldest_due_strict | 7.79 | 0.25 | 0.23 | ods-02-split 0.27 (1.13x); ods-06-head 0.33 (1.38x); ods-04-leftjoin 0.35 (1.45x); ods-05-leftjoin-straight 0.35 (1.47x); ods-08-min-of-limit 0.43 (1.81x); ods-00-main 6.41 (26.82x); ods-01-min 6.56 (27.46x); ods-03-split-min 9.97 (41.72x); ods-07-window 1171 (4899.77x) |
| oldest_due_nonstrict | 0.77 | 1.76 | 1.76 | odn-01-order 0.22 (0.13x); odn-02-min-of-limit 0.24 (0.13x); odn-05-no-completed-min/odn-00-main 1.63 (0.93x) |

#### S4 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 1.58 | 0.60 | 0.61 | ks-05-head 0.56 (0.93x); ks-03-leftjoin 0.57 (0.95x); ks-04-leftjoin-straight 0.58 (0.95x); ks-02-tuple 0.58 (0.97x); ks-01-split 0.64 (1.05x); ks-00-main 2870 (4747.12x); ks-07-window-part 3839 (6351.44x); ks-06-window 3849 (6366.78x) |
| keys_nonstrict | 85037 | 0.59 | 0.60 | kn-03-leftjoin 0.57 (0.95x); kn-05-head 0.58 (0.96x); kn-04-leftjoin-straight 0.58 (0.97x); kn-02-tuple 0.59 (0.99x); kn-01-split 1.02 (1.70x); kn-07-window-part 3361 (5615.13x); kn-06-window 3436 (5741.14x); kn-00-main 6373 (10649.09x); kn-08-groupby 6380 (10660.59x); kn-09-groupby-minid 6633 (11082.05x) |
| pages | 0.33 | 0.32 | 0.34 | pa-05-setstmt-nomerge 0.33 (1.00x); pa-01-latejoin 0.34 (1.04x); pa-00-main 0.36 (1.08x); pa-02-semi-in 0.53 (1.59x) |
| count_statuses | 85.30 | 117.0 | 118.8 | cs-02-split-null-sum 125.7 (1.07x); cs-06-incomplete 126.2 (1.07x); cs-03-split-null-count 434.1 (3.68x) |
| count_partitions | 358.5 | 128.8 | 130.3 | cp-04-incomplete 135.7 (1.05x); cp-02-split-null 137.5 (1.06x); cp-06-group-sum 199.8 (1.54x) |
| oldest_created | 79279 | 202.1 | 203.4 | oc-06-order-limit 0.27 (<0.01x); oc-03-order-id 0.28 (<0.01x); oc-02-min-of-limit 0.30 (<0.01x); oc-07-no-completed-min/oc-00-main 198.8 (0.98x) |
| oldest_due_strict | 19974 | 0.37 | 0.35 | ods-02-split 0.41 (1.15x); ods-04-leftjoin 0.51 (1.42x); ods-05-leftjoin-straight 0.52 (1.46x); ods-06-head 0.56 (1.57x); ods-08-min-of-limit 0.64 (1.79x); ods-00-main 2564 (7143.01x); ods-01-min 2625 (7311.52x); ods-07-window 3522 (9810.26x); ods-03-split-min 3864 (10762.86x) |
| oldest_due_nonstrict | 75281 | 202.2 | 204.9 | odn-02-min-of-limit 0.39 (<0.01x); odn-01-order 0.40 (<0.01x); odn-05-no-completed-min/odn-00-main 188.1 (0.92x) |

#### S5 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 4.69 / 4.67 | 3.35 | 3.40 | ks-05-head 0.60 (0.18x); ks-01-split 3.33 (0.98x); ks-03-leftjoin 3.42 (1.01x); ks-02-tuple 3.47 (1.03x); ks-04-leftjoin-straight 3.57 (1.06x); ks-00-main 472.7 (139.99x); ks-06-window 503.1 (149.01x); ks-07-window-part 511.0 (151.34x) |
| keys_nonstrict | 14408 / 13314 | 2.68 | 2.69 | kn-05-head 0.65 (0.24x); kn-01-split 2.68 (1.00x); kn-00-main 187.6 (69.90x); kn-08-groupby 192.5 (71.73x); kn-09-groupby-minid 204.7 (76.28x); kn-07-window-part 457.2 (170.35x); kn-06-window 458.9 (170.96x); kn-04-leftjoin-straight 1324 (493.41x); kn-03-leftjoin 1342 (499.93x); kn-02-tuple 1366 (508.99x) |
| pages | 4648 / 4265 | 1582 | 1555 | pa-05-setstmt-nomerge 1366 (0.87x); pa-02-semi-in 1440 (0.92x); pa-01-latejoin 1447 (0.92x); pa-00-main 4510 (2.88x) |
| count_statuses | 17.35 / 17.54 | 23.39 | 23.41 | cs-06-incomplete 24.66 (1.05x); cs-02-split-null-sum 24.91 (1.06x); cs-03-split-null-count 54.70 (2.34x) |
| count_partitions | 13281 / 13122 | 27.19 | 27.32 | cp-04-incomplete 28.23 (1.04x); cp-02-split-null 29.37 (1.08x); cp-06-group-sum 41.30 (1.52x) |
| oldest_created | 3229 / 2825 | 39.88 | 39.92 | oc-02-min-of-limit 0.28 (<0.01x); oc-06-order-limit 0.29 (<0.01x); oc-03-order-id 0.29 (<0.01x); oc-07-no-completed-min/oc-00-main 38.38 (0.96x) |
| oldest_due_strict | 3724 / 3428 | 0.63 | 0.62 | ods-06-head 0.51 (0.81x); ods-02-split 0.69 (1.10x); ods-04-leftjoin 0.78 (1.25x); ods-05-leftjoin-straight 0.79 (1.26x); ods-08-min-of-limit 0.85 (1.36x); ods-03-split-min 445.2 (711.74x); ods-00-main 449.2 (718.19x); ods-01-min 454.6 (726.81x); ods-07-window 483.7 (773.36x) |
| oldest_due_nonstrict | 38.01 / 37.30 | 41.80 | 42.32 | odn-01-order 0.35 (<0.01x); odn-02-min-of-limit 0.41 (<0.01x); odn-05-no-completed-min/odn-00-main 39.81 (0.95x) |

#### S6 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 417.5 / 385.0 | 0.78 | 0.78 | ks-04-leftjoin-straight 0.75 (0.96x); ks-03-leftjoin 0.75 (0.97x); ks-05-head 0.76 (0.98x); ks-02-tuple 0.77 (0.99x); ks-01-split 0.81 (1.04x); ks-00-main 63.93 (82.18x); ks-06-window 593.3 (762.62x); ks-07-window-part 598.3 (769.01x) |
| keys_nonstrict | 405.1 / 372.5 | 0.77 | 0.77 | kn-05-head 0.76 (0.99x); kn-02-tuple 0.77 (1.00x); kn-01-split 0.85 (1.11x); kn-00-main 46.85 (60.76x); kn-03-leftjoin 360.4 (467.43x); kn-04-leftjoin-straight 360.5 (467.57x); kn-09-groupby-minid 371.4 (481.74x); kn-08-groupby 373.0 (483.76x); kn-07-window-part 376.0 (487.69x); kn-06-window 378.8 (491.35x) |
| pages | 0.36 / 0.35 | 0.32 | 0.30 | pa-05-setstmt-nomerge 0.31 (1.00x); pa-00-main 0.33 (1.06x); pa-01-latejoin 0.35 (1.12x); pa-02-semi-in 0.51 (1.63x) |
| count_statuses | 17.74 / 17.69 | 23.54 | 23.41 | cs-02-split-null-sum 24.85 (1.06x); cs-06-incomplete 24.90 (1.06x); cs-03-split-null-count 54.48 (2.32x) |
| count_partitions | 24.66 / 24.79 | 27.10 | 27.18 | cp-04-incomplete 28.56 (1.05x); cp-02-split-null 28.73 (1.06x); cp-06-group-sum 41.22 (1.52x) |
| oldest_created | 7516 / 7072 | 40.75 | 40.41 | oc-06-order-limit 0.28 (<0.01x); oc-02-min-of-limit 0.28 (<0.01x); oc-03-order-id 0.29 (<0.01x); oc-07-no-completed-min/oc-00-main 39.09 (0.96x) |
| oldest_due_strict | 442.6 / 383.3 | 0.40 | 0.38 | ods-02-split 0.45 (1.15x); ods-04-leftjoin 0.52 (1.35x); ods-05-leftjoin-straight 0.55 (1.40x); ods-06-head 0.55 (1.42x); ods-08-min-of-limit 0.60 (1.54x); ods-00-main 46.60 (119.65x); ods-01-min 357.7 (918.27x); ods-03-split-min 374.1 (960.52x); ods-07-window 573.7 (1472.81x) |
| oldest_due_nonstrict | 1.77 / 1.76 | 1.42 | 1.42 | odn-01-order 0.38 (0.26x); odn-02-min-of-limit 0.41 (0.29x); odn-05-no-completed-min/odn-00-main 31.68 (22.34x) |

#### S7 (query forms on the b-base schema; median ms; ratio = form / mean(b-base, b-base2))

| query | main | b-base | b-base2 | forms (median ms, sorted) |
|---|---|---|---|---|
| keys_strict | 1.21 | 0.59 | 0.59 | ks-05-head 0.56 (0.94x); ks-04-leftjoin-straight 0.57 (0.95x); ks-03-leftjoin 0.58 (0.97x); ks-02-tuple 0.58 (0.97x); ks-01-split 0.63 (1.06x); ks-00-main 22.18 (37.34x); ks-06-window 1215 (2045.91x); ks-07-window-part 1247 (2099.53x) |
| keys_nonstrict | 758.0 | 0.58 | 0.61 | kn-05-head 0.57 (0.96x); kn-02-tuple 0.59 (0.99x); kn-04-leftjoin-straight 0.60 (1.01x); kn-03-leftjoin 0.60 (1.01x); kn-01-split 0.67 (1.13x); kn-00-main 12.36 (20.80x); kn-08-groupby 12.60 (21.21x); kn-07-window-part 23.87 (40.19x); kn-06-window 24.18 (40.71x); kn-09-groupby-minid 37.65 (63.38x) |
| pages | 0.33 | 0.32 | 0.33 | pa-00-main 0.34 (1.03x); pa-01-latejoin 0.34 (1.04x); pa-05-setstmt-nomerge 0.34 (1.04x); pa-02-semi-in 0.51 (1.56x) |
| count_statuses | 48.18 | 68.84 | 68.34 | cs-06-incomplete 71.45 (1.04x); cs-02-split-null-sum 72.38 (1.06x); cs-03-split-null-count 209.4 (3.05x) |
| count_partitions | 890.4 | 1.68 | 1.72 | cp-04-incomplete 1.75 (1.03x); cp-02-split-null 1.94 (1.14x); cp-06-group-sum 2.69 (1.58x) |
| oldest_created | 883.6 | 2.30 | 2.32 | oc-06-order-limit 0.27 (0.12x); oc-03-order-id 0.28 (0.12x); oc-02-min-of-limit 0.29 (0.13x); oc-07-no-completed-min/oc-00-main 2.29 (0.99x) |
| oldest_due_strict | 914.7 | 0.36 | 0.37 | ods-02-split 0.42 (1.14x); ods-04-leftjoin 0.50 (1.37x); ods-05-leftjoin-straight 0.52 (1.41x); ods-06-head 0.52 (1.41x); ods-08-min-of-limit 0.59 (1.61x); ods-00-main 19.10 (52.18x); ods-01-min 19.41 (53.02x); ods-03-split-min 32.58 (89.00x); ods-07-window 1215 (3319.80x) |
| oldest_due_nonstrict | 2.72 | 2.53 | 2.63 | odn-01-order 0.34 (0.13x); odn-02-min-of-limit 0.40 (0.15x); odn-05-no-completed-min/odn-00-main 2.36 (0.91x) |


### 4.1 Best form per query (against `b-base` and `mdb-main`)

| query | best form | vs b-base | vs mdb-main | verdict |
|---|---|---|---|---|
| oldest_created | `oc-03-order-id` (= `oc-06`, `oc-02` within 0.03 ms) | 0.15-0.29 ms vs 1.8-203 ms | below main everywhere (main 3 ms - 79 s) | **win** (M2: ORDER BY ... LIMIT beats MIN; `oc-02` min-of-limit equal) |
| oldest_due_nonstrict | `odn-01-order` (`odn-02` +0.02 ms) | 0.18-0.40 ms vs 1.4-204 ms | below main at every label (S6 0.38 vs 1.77, S8-S7 0.22 vs 0.77) | **win** |
| keys_nonstrict | `kn-05-head` | S8-S5 0.52 vs 4.8 ms (0.11x), S5 0.65 vs 2.7 (0.24x); elsewhere 0.96-1.00x (no difference) | S8-S5 2.4 s, S5 14 s, S4 85 s for main; branch already far below | **win** at hot-key labels (S5 family), no difference elsewhere |
| keys_strict | `ks-05-head` | S8-S5 53 vs 107 ms (0.50x), S5 0.60 vs 3.4 ms (0.18x); elsewhere 0.93-1.00x | S8-S5 main 15 s; S5 main 4.7 ms | **win** at S8-S5 / S5, no difference elsewhere |
| oldest_due_strict | none (`ods-02-split` +10-15 %, `ods-06-head` 0.67x at S8-S5 but 1.4-1.6x at 6 other labels, absolute < 0.2 ms) | base 0.23-0.63 ms | base far below main (0.6 vs 22 s on S4) | no winner |
| pages | none (`pa-05` mariadb-only 0.87x at S5 only, `pa-01` 0.92x at S5, others 1.0-1.6x) | S8-S5 / S5 1.2-1.6 s unchanged: the cost is returning 18-20 k wide rows | main 5 s; `pa-00-main` (no `completed_at`) 3-3.5x worse than base | no difference |
| count_statuses | none (`cs-06` / `cs-02` +4-8 %, `cs-03` 2-4x) | base 23-117 ms | main (two counts) 17-176 ms; base +25-40 % at S6/S5, below main at S4 | no difference; `b-e5l` -5 % |
| count_partitions | none (`cp-04` / `cp-02` +3-9 %, `cp-06` +26-58 %) | base 1.4-129 ms | main 0.6-24 ms at partial ownership; main loses at S4 (359 ms), S5 (13 s), S7 (890 ms) | no difference; the S16/S8 gap to main (~4-14x) needs `(status, partition_no)` (round 1a) |

Notes by query:

- **M6 keys (S8-S5 and S8-S7 first).** S8-S5 `keys_strict`: `ks-05-head` 53.3, `ks-03/04` 94.7, `ks-01` 105.8, `ks-02` 106.7, `ks-00-main` 117, `ks-07` 131, `ks-06` 508 (base 108/107). `keys_nonstrict`: `kn-05-head` 0.52, `kn-01` 4.4, then main-shaped `kn-00-main` / GROUP BY forms 56-61 (12x worse), `kn-03/04/02` (LEFT JOIN, tuple) 1.3-1.4 s (280x worse, the anti-join/row-constructor prevents the early stop on hot keys), windows 105-465. S8-S7: all NOT EXISTS forms 0.38-0.50 (no difference), main-shaped forms 4.6-5.4 (11x). Interpretation: `ks-05` / `kn-05` (`o.id = (SELECT id ... ORDER BY created_at, id LIMIT 1)`) replace the per-candidate probe of 1-2 k rows of a hot key by one index-ordered row; `keys_strict` S8-S5 still reads ~200 k outer rows (r_rows 199 980, plan unchanged), so the remaining 53 ms is the outer scan; at S5 the outer is stopped early (0.60 ms). Plans (S8-S5): base `keys_nonstrict` `earlier` probe examined 40 000 rows (r_index_rows 2000 x 20 loops), `kn-05` 80 rows; base `keys_strict` `earlier` 51 983 rows examined, `ks-05` 17.
- **M3.** `oldest_due_strict`: `ods-01-min` (MIN) is 2.6 ms - 2.6 s / 100-450 ms at hot labels (no early stop), 27-7300x slower than the ordered base; `ods-02-split` +10-15 %; M7: no `ods-*` form reaches < 1 ms without i3 (all use the outer scan on `(status, completed_at, next_retry_at, ...)`), so i1, i2, i3 stays the minimal set. `odn-00-base` (MIN) vs `odn-01-order`: 142 -> 0.23 ms (S8-S4).
- **M1 query reverts (`b-m1q`).** Main SQL on the `b-base` schema, ratio against the base: `ks-00-main` 1.09x at S8-S5 (no difference) but 19x (S8-S7), 37x (S7), 82x (S6), 140x (S5), 2800x (S8-S4), 4700x (S4); `kn-00-main` 11x (S8-S5) to 3600x (S8-S4); `ods-00-main` 27x (S8-S7) to 7100x (S4); `pa-00-main` (no `completed_at`) 3-3.5x at hot keys, 1.0-1.1x elsewhere; `oc-00-main` = MIN = no difference to the base (full NEW range scan). Reverting a B-2/B-3/B-7 query is a loss on this DDL; B-4 (`pages` with `completed_at IS NULL`) matters only at hot keys.
- **Window forms (`ks-06/07`, `kn-06/07`, `ods-07`):** control forms, 100 - 13 000x slower (full materialization).
- **Order-changing forms:** `kn-08/09` (GROUP BY) are WARN on S5 (tie order) and 12-76x slower than the base on S5, 1500-6600 ms on S4: rejected.

## 5. Part 3 — M5 `pages` (derived key tables) on `b-base`

Harness extension (README "MariaDB port"): `:recordKeysJson`, `:recordKeysRows` -> `(?),(?)`, per-key UNION ALL arm expansion; took well under an hour. Labels S8-S5, S5 (keys from `keys_strict`, 9-10 keys x 2000 rows) and S4-b100 (`--batch 100`, `--pages-keys-file experiments/r1/s4-pages-keys-b100.txt`, the first 100 distinct keys in `created_at, id` order of due NEW rows; first 10 equal `s4-pages-keys.txt`). Median ms, `results/r1b/m5-<form>/`.

| form | S8-S5 (18 000 rows) | S5 (20 000 rows) | S4-b100 (100 rows) |
|---|---|---|---|
| pa-00-base (`IN (:recordKeys)`) | 1283 | 1434 | 1.27 |
| pa-01-latejoin | 1276 | 1424 | 1.25 |
| pa-02-semi-in | 1350 | 1496 | 1.72 |
| pa-06-json-table | 1268 | 1400 | 1.29 |
| pa-08-values-cte | 1262 | 1408 | 1.25 |
| pa-09-union-per-key | 1279 | 1417 | 3.20 |

**No difference** (S8-S5 / S5 all within the 11 % HOT threshold of the base, `pa-02` +5 %; at S4-b100 `pa-02` is 1.35x and `pa-09` 2.5x, sub-4 ms). The plans of base / json / values are the same key-index range or one probe per key (`pa-06`/`pa-08` add a filesort and a derived table); at hot keys the time is the transfer of 18-20 k rows, not the access path. A derived key table is not worth a code change. (Part 3 results are one pass, measured after parts 1-2.)

## 6. Ranking and deferred tests

1. `b-base` + `oc-03-order-id` + `odn-01-order` (+ `kn-05-head`, `ks-05-head` if the hot-key gain justifies the change): fixes every `oldest_*` weak cell of `b-base`; no write cost.
2. `b-e5l` with its overlays: same read result, base-level write cost, -3.8 % index size, needs the E5 invariant. Equal to (1) within noise; (1) is contract-safe.
3. `b-m4`: equal to `b-base`.
4. Everything else (splits, tuples, left joins, windows, GROUP BY, `pages` forms): no gain or loss.

Deferred (quiet host / quiet machine): `ks-05` vs `ks-03/04` (7-12 % at S8-S5) is outside what a single pass can separate from a 0.88x; `pa-05-setstmt-nomerge` 0.87x at S5 (single pass, MariaDB-only syntax); `ods-06-head` vs base (sub-0.2 ms absolute); `b-e5l` vs `b-base` count_* (-5..-9 %); S10 throughput (single runs, 420-444 rec/s all equal). None is a contender once (1) is confirmed, except `ks-05` / `kn-05` which go to confirm.

## 7. Equivalence for the survivors (S7)

`b-qf1` (candidate), `b-qf5` (`ks-05-head`, `kn-05-head`, `ods-05`), `b-qf6` (`ods-06-head`) and `b-e5l` on S7 (FAILED records): 26 PASS each, 0 FAIL, 0 WARN (SKIP: `find_pending_all`, `find_failed_all`, S1/S3 only).

## 8. Proposed confirm list (best form per query on the best DDL)

DDL: **`b-base`** (i1, i2, i3 = `r1-m1d`; `b-m4` is the same index, `b-e5l` equal but invariant-dependent).

| query | form | reason |
|---|---|---|
| oldest_created | `oc-03-order-id` | 0.15-0.29 ms at every label |
| oldest_due_nonstrict | `odn-01-order` | 0.18-0.40 ms at every label |
| keys_strict | `ks-05-head` | 0.50x at S8-S5, 0.18x at S5, no difference elsewhere; PASS S1/S5/S7 |
| keys_nonstrict | `kn-05-head` | 0.11x at S8-S5, 0.24x at S5, no difference elsewhere; PASS S1/S5/S7 |
| oldest_due_strict | branch SQL (`ods-00-base`) | no form wins |
| pages, count_statuses, count_partitions | branch SQL | no form wins; `count_partitions` P2 gap to main needs an index and is not carried (round 1a) |

Confirm run: all labels, S2-wc / S4-wc (no DDL change vs `b-base`, so a repeat only for the combined candidate is needed for S10/S9), equivalence S1/S5/S7 with the combined four overlays (each pack passed alone; the combination was not run), S9 and S10 per the brief.

Risks: the zero-row (instead of NULL) result of the two `ORDER BY ... LIMIT` forms needs the repository to treat "no row" as none; `ks-05` / `kn-05` rely on `id` determinism of the head (ordering `(created_at, id)`, PASS in equivalence); no index hints, no generated columns, no schema change beyond the existing index set.
