# E04 / MIN: partial record-key index and the minimal index set (round 2, PostgreSQL)

Author: analysis sub-agent. No measurements were made for this report; every number is read from the files cited. Result dirs are
`docs/db-postgresql/results/<variant>/postgres/<label>-R2/` (abbreviated `<variant>/<label>`), `timings.csv`, `plancache.csv`, `plans/`.
Harness mechanics, spec: `experiments/round2-spec.md`. Equivalence: `results/equiv/postgres/{S2,S5,S7}/{E04,MIN}-vs-v1.md`.

## Hypothesis, base, change

| Variant | Base | Indexes on `outbox_record` | Query overrides |
|---|---|---|---|
| `v1b` (reference) | v1 | pkey, `(partition_no,status,next_retry_at)`, `(status,next_retry_at)`, `(status)`, `(record_key,completed_at,created_at,id)`, partial queue index `(status,created_at,id) INCLUDE (partition_no,next_retry_at,record_key) WHERE completed_at IS NULL` | `pages` base `IN` form (`experiments/R2-v1b/`) |
| `E04` | v1b | v1b with the 118 MB record-key index replaced by `idx_outbox_record_key_created_id_incomplete (record_key, created_at, id) WHERE completed_at IS NULL`; the three status/partition indexes stay (6 indexes) | `pages`: `AND completed_at IS NULL ORDER BY record_key, created_at, id`; `find_incomplete_by_key`: `+ AND completed_at IS NULL` (`experiments/R2-E04/`) |
| `MIN` | v1b | pkey + partial queue index + the partial record-key index (3 indexes). Drops `idx_status`, `idx_status_retry`, `idx_partition_status_retry` and the full record-key index | A6 literal `AND completed_at IS NULL` on `count_new`, `count_failed`, `count_statuses` (NEW/FAILED form), `count_partitions`, `count_partition_single`, `oldest_due_nonstrict`, `find_failed_before`, `ids_failed_before`, plus the E04 overrides (`experiments/R2-MIN/`). **`oldest_due_strict` has no override** (see below) |
| `A6` | v1b | like MIN, but with the full record-key index | A6 only |

Hypothesis: with invariant `completed_at IS NULL <=> status IN (NEW, FAILED)`, the record-key index only needs the incomplete rows
(P1 ordering check `earlier.completed_at IS NULL`). A partial index shrinks 118 MB to ~1.6 MB at load, cuts write cost, and keeps P1 reads.
MIN tests whether the partial queue index can also serve P2 so that three more indexes can go.

## Method and noise rules

- Rank by P1 (keys_strict, keys_nonstrict, pages, insert/upd_*/delete_by_id, S10), then P2 (count_statuses, count_partitions, oldest_*); P3 reported, does not block.
- **Partial ownership first** (user decision 2026-11-02): S8-S2, S8-S4 (`owned=64` of 256 partitions, `S8-S2-R2/env.txt`) and S10 with 4 pollers. S2, S4, S5, S6, S7 use **full ownership**; **S5/S6/S7 were measured with full ownership only** (there is no S8 variant of them).
- Significance: relative change above the group threshold (HOT/WRITE 30 %, MON/RET/ADMIN 20 %, `STATE.md`) **and** more than 0.15 ms. Bold in tables = significant. `?` = `plancache.csv` shows a different plan-mode mix between the compared variants (suspect cell, do not interpret).
- Mode letter after a median: `C` all 20 timed reps custom plan, `G` all generic, `M` mixed (4 custom + 16 generic: the pgjdbc switch after 5 executions). Source: `plancache.csv` of the same dir.
- Single run each (one measurement per variant and label, rotated order) unless said otherwise. Only S10 (two passes) and `writecost` (v1, v1b, MIN twice) have repeats.
- Tables below: medians in ms, 20 reps. Raw tables with all variants: generated from the `timings.csv` files listed above.

## Equivalence

`results/equiv/postgres/S2/E04-vs-v1.md`, `.../MIN-vs-v1.md`, and the same for S5 and S7: 26 PASS, 2 SKIP (`find_pending_all`, `find_failed_all`, unbounded), no WARN/FAIL, for both variants. Caveat: the equivalence run uses the override's `count_statuses` (NEW/FAILED only), so it does not cover COMPLETED (see "What breaks").

## Results: partial ownership (primary)

Medians in ms, base = v1b. Sources: `{v1,v1b,E04,MIN}/S8-S2-R2/timings.csv`, `{...}/S8-S4-R2/timings.csv`, plan modes `.../plancache.csv`.

### S8-S2 (1 M rows, 1.2 k NEW, 12.8 k FAILED, 64 owned partitions)

| query (group) | v1 | v1b | E04 | MIN | E04 vs v1b | MIN vs v1b |
|---|---:|---:|---:|---:|---:|---:|
| keys_strict (HOT) | 1.97 C | 1.08 C | 1.05 C | 0.88 C | -3 % | -19 % (0.20 ms, within 30 %) |
| keys_nonstrict (HOT) | 1.97 C | 1.45 C | 1.35 C | 1.30 C | -7 % | -10 % |
| pages (HOT) | 0.83 M | 1.04 C | 1.01 C | 0.97 C | -3 % | -7 % |
| insert (WRITE, rolled back) | 0.34 M | 0.37 M | 0.36 M | 0.35 M | -2 % | -4 % |
| upd_complete / upd_retry / upd_fail | 0.32 / 0.32 / 0.20 | 0.35 / 0.32 / 0.29 | 0.28 / 0.25 / 0.29 | 0.33 / 0.29 / 0.24 | within noise | within noise |
| delete_by_id | 0.14 M | 0.19 M | 0.15 M | 0.17 M | within noise | within noise |
| count_statuses (P2) | 1.23 C | 1.33 C | 1.24 C | 1.95 C | -6 % | **+47 %** (+0.62 ms) |
| count_partitions (P2) | 0.65 C | 0.47 C | 0.49 C | 0.59 C | +5 % | +26 % (0.12 ms, below 0.15) |
| oldest_created (P2) | 1.17 C | 0.36 C | 0.39 C | 0.35 C | +8 % | -4 % |
| oldest_due_strict (P2) | 0.68 C | 0.61 C | 0.58 C | **186.25 C** | -5 % | **+30 533 %** |
| oldest_due_nonstrict (P2) | 0.25 M | 0.17 M | 0.25 M | 1.00 C | +46 % (0.08 ms, below 0.15) ? | **+491 %** (+0.83 ms) ? |
| count_partition_single (deprecated metrics) | 0.16 C | 0.17 C | 0.19 C | 0.38 C | within noise | **+123 %** (+0.21 ms) |

P3 (S8-S2): `count_completed` 28.8 -> 29.4 (E04) -> **104.7** (MIN); `delete_by_key_status` 0.24 / 0.27 / 0.32 / **164.9** ms (MIN seq scan); `find_failed_before` 0.27 -> **5.9** ms, `ids_failed_before` 0.23 -> **1.3** ms, `delete_by_status_failed` 12.2 -> **198** ms (MIN); `find_completed_before`/`ids_completed_before` 511/506 -> 251/274 ms (MIN faster, `?`); `find_incomplete_by_key` 0.31 -> 0.10 (E04) / 0.12 (MIN).

### S8-S4 (backlog, ~1 M NEW rows, 64 owned partitions; `S8-S4-R2/env.txt`)

| query (group) | v1 | v1b | E04 | MIN | E04 vs v1b | MIN vs v1b |
|---|---:|---:|---:|---:|---:|---:|
| keys_strict (HOT) | 0.99 C | 0.93 C | 0.87 C | 0.88 C | -7 % | -6 % |
| keys_nonstrict (HOT) | 0.99 C | 1.00 C | 0.92 C | 0.80 C | -8 % | -20 % (0.20 ms, within 30 %) |
| pages (HOT) | 0.70 M | 0.84 M | 0.69 M | 0.69 M | -19 % | -19 % |
| insert (WRITE) | 0.55 M | 0.53 M | 0.52 M | 0.36 M | -3 % | -32 % (0.17 ms; **single run**, `writecost` shows -10 to -26 %) |
| upd_complete / upd_retry / upd_fail | 0.28 / 0.28 / 0.25 | 0.37 / 0.33 / 0.34 | 0.26 / 0.27 / 0.29 | 0.30 / 0.27 / 0.23 | within noise | within noise |
| count_statuses (P2) | 38.7 C | 38.3 C | 38.6 C | **119.4 C** | +1 % | **+211 %** (+81 ms) |
| count_partitions (P2) | 16.1 M | 16.5 C | 16.3 C | **122.1 C** | -2 % | **+639 %** (+106 ms) |
| oldest_created (P2) | 0.37 C | 0.41 C | 0.35 C | 0.45 C | -16 % | +8 % |
| oldest_due_strict (P2) | 0.69 C | 0.71 C | 0.57 C | **446.3 C** | -19 % | **+63 022 %** |
| oldest_due_nonstrict (P2) | 0.24 M | 0.25 M | 0.28 M | **146.3 C** | +11 % | **+57 508 %** ? |
| count_new / count_failed (P2 deprecated) | 29.7 M / 21.8 G | 29.6 C / 0.25 C | 29.2 C / 0.19 C | 157.9 M / 22.0 G | n.s. | `?` (count_failed is a plan-mode flip, 0.25 vs 22 ms, also between v1 and v1b) |

P3 (S8-S4): `delete_by_key_status` 0.12 (v1b) -> **413** (E04) / **280** (MIN) ms; `delete_by_status_failed` 0.17 -> **230** (MIN) ms; `count_completed` 29.6 -> 192.9 (MIN) ms `?`; `find_completed_before`/`ids_completed_before` 509/512 -> 393/387 (MIN), -23 %/-24 %.

### S10 concurrency, 4 pollers (`<variant>/S10-R2/concurrency-p4-r0.txt`, pass 2 = `S10b-R2`, reverse variant order)

| variant | pass 1 rec/s | pass 2 rec/s | mean | pages p50 ms (pass 1 / 2) | keys p50 ms (1 / 2) |
|---|---:|---:|---:|---|---|
| v1 | 1296.4 | 1024.6 | 1160 | 0.57 / 1.31 | 5.64 / 11.90 |
| v1b | 1055.4 | 1051.6 | 1054 | 14.18 / 14.33 | 2.11 / 1.75 |
| E04 | 923.1 | 1276.4 | 1100 | 15.35 / 1.04 | 1.98 / 4.91 |
| MIN | 1348.4 | 1059.2 | 1204 | 0.90 / 13.19 | 3.92 / 2.13 |

Noise from the two passes: p4 spread between passes is 0 % (v1b) to 38 % (E04); p1 spread -29 % to +34 % (v1b 333.5 -> 236.6, E04 211.6 -> 283.1, MIN 210.7 -> 220.1; `.../concurrency-p1-r0.txt`). **S10 cannot separate v1b, E04 and MIN** (differences of the means: +4 % E04, +14 % MIN, each smaller than the pass-to-pass spread). The variation follows the `pages` poller query: it is either ~1 ms or 13-20 ms in the same variant (v1b-family `IN` form: 11 of 16 runs in the slow mode, v1 `unnest` form: 0 of 4). Whether this is a generic/custom plan flip was not verified (no `plancache.csv` for S10). The throughput winners are the runs where `pages` stayed fast (MIN 1348, E04 1276, v1 1296), not the index set.

## Results: full ownership (secondary; S5/S6/S7 only exist with full ownership)

Sources: `{v1,v1b,E04,MIN}/{S2,S4,S5,S6,S7}-R2/timings.csv`. Only HOT/P2 rows that carry a signal.

| label | query | v1 | v1b | E04 | MIN | E04 vs v1b | MIN vs v1b |
|---|---|---:|---:|---:|---:|---:|---:|
| S2 | keys_strict | 2.16 | 1.35 | 1.14 | 1.06 | -15 % | -22 % (0.29 ms) |
| S2 | keys_nonstrict | 2.11 | 1.09 | 1.05 | 1.01 | -3 % | -7 % |
| S2 | pages | 0.72 M | 0.64 M | 0.97 C | 0.96 C | **+52 %** ? | **+51 %** ? (plan-mode flip, M vs C; S8-S2 pages is identical across variants) |
| S2 | count_statuses | 1.28 | 1.21 | 1.29 | 1.84 | +7 % | **+52 %** (+0.63 ms) |
| S2 | oldest_due_strict | 0.90 | 0.88 | 0.85 | **189.7** | -4 % | **+21 357 %** |
| S2 | oldest_due_nonstrict | 0.41 M | 0.38 M | 0.39 M | 1.15 C | +2 % | **+201 %** ? |
| S4 | keys_strict / keys_nonstrict | 1.54 / 1.17 | 1.25 / 1.22 | 1.15 / 1.12 | 1.19 / 1.02 | n.s. | n.s. |
| S4 | count_statuses / count_partitions | 38.4 / 64.4 | 38.4 / 64.7 | 38.8 / 65.6 | 121.6 / 119.3 | n.s. | **+216 %** / **+84 %** |
| S4 | oldest_due_strict | 0.94 | 0.99 | 0.83 | **721.2** | -16 % | **+72 969 %** |
| S4 | insert / upd_complete / upd_retry | 0.48 / 0.41 / 0.32 | 0.46 / 0.33 / 0.27 | **0.86 / 0.56 / 0.75** | 0.55 / 0.34 / **0.65** | **+87 % / +67 % / +173 %** (suspect) | +20 % / +1 % / **+138 %** (suspect) |
| S5 | keys_strict / keys_nonstrict | 1.27 / 1.13 | 1.35 / 1.24 | 1.25 / 1.26 | 1.34 / 1.25 | n.s. | n.s. |
| S5 | pages | 135.8 | 94.7 | 97.8 | 92.3 | +3 % ? | -3 % ? |
| S5 | oldest_due_strict | 1.01 | 0.93 | 0.90 | **146.9** | -3 % | **+15 679 %** |
| S6 | keys_strict / keys_nonstrict / pages | 1.36 / 1.25 / 0.71 | 1.21 / 1.21 / 0.73 | 1.26 / 1.18 / 0.65 | 1.14 / 1.08 / 0.67 | n.s. | n.s. |
| S6 | oldest_due_strict | 1.01 | 1.21 | 0.77 | **194.5** | **-37 %** (0.44 ms) | **+15 950 %** |
| S7 (390 k FAILED) | keys_strict / keys_nonstrict | 13.22 / 12.91 | 1.43 / 1.08 | 1.17 / 1.07 | 1.20 / 1.19 | n.s. | n.s. (all ~-91 % vs v1) |
| S7 | count_statuses / count_new / count_failed | 27.1 / 0.70 / 21.8 | 26.8 / 0.69 / 22.0 | 26.8 / 0.72 / 21.7 | 38.1 / 1.20 / 33.9 | n.s. | **+42 % / +73 % / +54 %** |
| S7 | oldest_due_strict | 0.92 | 0.84 | 0.86 | **201.5** | +2 % | **+23 741 %** |

Reading the full-ownership P1 reads: no significant difference between v1b, E04 and MIN in keys_*/pages on any label; both beat v1 only where v1b already did (S2 keys_*, S7 -91 %). The S4 insert/upd_* cells for E04 and MIN (`S4-R2/timings.csv`) look like a write penalty (up to 0.75 ms vs 0.27), but the plan of `upd_retry` is identical in v1b, E04 and MIN (pkey index scan, 0.04 ms execution, `S4-R2/plans/upd_retry.txt`), the mode is the same (all generic), and the committed `writecost` runs on S2 show no such penalty (below). These are the known S4 write-cell outliers of round 1; not attributable to an index, single run.

## Plans

Excerpts from `<variant>/S8-S2-R2/plans/<q>.json` and `.../S8-S4-R2/plans/<q>.json` (exec times are the single `EXPLAIN ANALYZE` capture, not the median).

| query | v1b / E04 | MIN |
|---|---|---|
| keys_strict | S8-S2: `Nested Loop` over `Index Only Scan idx_status_created_id_incomplete` (10 rows, 8 buffers) with an anti-join probe on the record-key index (v1b: `idx_record_key_completed_created_id`, 42 buffers; E04/MIN: `idx_record_key_created_id_incomplete`, 32 buffers); exec 0.14 / 0.17 / 0.11 ms. S8-S4 the same, 41-45 buffers | same as E04 (partial record-key probe), 40 buffers |
| keys_nonstrict | S8-S2: `Sort` over `Hash Join` of two Index Only Scans of the partial queue index (56 buffers, exec ~0.55-0.59 ms), identical in v1b/E04/MIN. S8-S4: `Nested Loop`, partial queue index + record-key index probe (`Index Scan`, 50 buffers), exec 0.10 ms | identical to E04 |
| pages | S8-S2: `Sort` over `Index Scan idx_status_created_id_incomplete` (205 buffers) in all three. S8-S4: `Index Scan` on the record-key index (v1b 48 buffers, E04/MIN partial index 48 buffers; exec 0.14 / 0.07 / 0.09 ms) | partial record-key index, same buffers |
| count_statuses | S8-S2: `Aggregate` over `Index Only Scan idx_status` (14 000 rows, 50 buffers, 1.55 / 1.68 ms). S8-S4: parallel `Index Only Scan idx_status`, 893 buffers, 52 ms | `Index Only Scan` of the partial queue index: S8-S2 279 buffers, 2.3 ms; S8-S4 17 388 buffers, 139 ms (it reads every incomplete entry and all of its `INCLUDE` data) |
| count_partitions | S8-S2: `Index Only Scan idx_partition_status_retry` (278 rows, 260 buffers, 0.17 ms). S8-S4: same index, 250 815 rows, 1718 buffers, 27 / 25.5 ms | partial queue index: S8-S2 `Index Only Scan` 57 buffers (205 heap fetches), 0.21 ms; S8-S4 17 382 buffers, parallel scan of all NEW entries, 117 ms |
| oldest_created | `Limit` over `Index Only Scan` of the partial queue index (1 row, 4-7 buffers, 0.03 ms) in all three | same |
| oldest_due_strict | `Nested Loop`: `Index Scan idx_status_retry` (ordered by next_retry_at, stops at first row) with a record-key probe (v1b full index 5 buffers; E04 partial 4-5 buffers); 0.06-0.07 ms | **S8-S2: parallel `Seq Scan` on `outbox_record` (116 015 buffers) + sort, 118.7 ms; S8-S4: Seq Scan + sort, 226 813 buffers, 255 ms**. The outer query has no `o.completed_at IS NULL`, so the partial queue index cannot be used; `idx_status_retry` is gone |
| oldest_due_nonstrict | `Limit` over `Index Scan idx_status_retry` (`MIN` rewritten to an initplan, 5-10 buffers, 0.04-0.07 ms) | `Aggregate` over `Index Only Scan` of the partial queue index: S8-S2 229 rows 57 buffers 0.38 ms; S8-S4 83 605 rows 17 382 buffers 122 ms (the index is ordered by created_at, not next_retry_at, so the whole partition slice is read) |

## Write cost and storage (S2 `writecost`)

Sources: `<variant>/S2-R2/writecost.csv`; repeats `<variant>/S2-wc2-R2/writecost.csv` (v1, v1b, MIN only). Committed writes, 5 000 each, 1 thread / 4 threads.

| metric | v1 (run1/run2) | v1b (run1/run2) | A6 | E04 | MIN (run1/run2) |
|---|---|---|---|---|---|
| index count (incl. pkey) | 6 | 6 | 3 | 6 | 3 |
| insert 1 thread p50 ms | 2.015 / 1.911 | 2.059 / 2.044 | 1.521 | 1.904 | 1.517 / 1.843 |
| insert 1 thread throughput /s | 487.5 / 502.4 | 485.1 / 484.5 | 597.1 | 523.7 | 588.5 / 541.3 |
| insert 1 thread WAL bytes/write | 6642 / 6721 | 6731 / 6696 | 6117 | 6788 | 6127 / 6051 |
| insert 4 threads p50 ms | 2.013 / 1.845 | 1.862 / 1.851 | 1.776 | 1.828 | 1.856 / 1.861 |
| insert 4 threads WAL bytes/write | 6831 / 6846 | 6848 / 6862 | 6254 | 6826 | 6221 / 7163 |
| upd_complete p50 ms | 2.158 / 2.035 | 2.136 / 2.129 | 1.949 | 1.774 | 1.886 / 2.024 |
| upd_complete WAL bytes/write | 7520 / 7515 | 7552 / 7507 | 6850 | 7015 | 6375 / 6327 |
| indexes total before -> after (MB) | 276.5 -> 281.3 | 276.5 -> 281.4 | 193.0 -> 197.6 | 160.2 -> 164.8 | **76.7 -> 81.1** |
| record-key index after (MB) | 119.9 (full, +id) | 119.9 | 119.9 | **3.3 (partial)** | **3.3 (partial)** |
| `idx_status` / `idx_status_retry` / `idx_partition_status_retry` (MB) | 6.9 / 38.5 / 38.4 | same | dropped | same | dropped |

Noise (repeat vs repeat, same variant): insert 1-thread p50 up to 5 % (v1, v1b), 21 % for MIN (1.517 vs 1.843); WAL/write 1.2 % (1-thread), up to 15 % (MIN 4 threads, 6221 vs 7163, probably concurrent autovacuum/checkpoint); upd_complete p50 up to 7 %; upd_complete WAL/write below 1 %.

- **E04 vs v1b:** removes the 118 MB record-key index (-116 MB, index total 276 -> 160 MB, -42 %). Latency: no difference beyond noise (insert 1.90 vs 2.06 ms, -8 %; upd_complete 1.77 vs 2.14, -17 %, single run). WAL per write: insert unchanged (6788 vs 6731), upd_complete **-7 %** (7015 vs 7552; noise below 1 %). So the saving is storage and a small WAL/update effect, not insert cost.
- **MIN vs v1b:** index total 276 -> 77 MB (-72 %; 3 indexes). WAL/write insert **-9 %** (6127 vs 6731; repeat 6051 vs 6696, -10 %), upd_complete **-16 %** (6375 vs 7552; repeat 6327 vs 7507). Latency: insert 1-thread p50 -26 % in run 1, -10 % in the repeat (run-to-run spread 21 %, so only the WAL numbers are solid); 4-thread latency and throughput unchanged (1.856 vs 1.862 ms); upd_complete p50 -12 % / -5 % (within noise). MIN vs A6: same WAL, so the extra gain comes from the dropped status/partition indexes, not the record-key index.
- Heap: `table_bytes` identical in all variants (961 052 672 after).

## S9 churn (500 000 inserts, 500 000 retention deletes, no manual VACUUM; single run each)

Sources: `<variant>/S9-R2/churn.txt` (sizes and `pg_stat_user_tables` before/after).

| variant | churn seconds | table after (MB) | indexes after (MB) | record-key index after | n_dead_tup after | autovacuum count |
|---|---:|---:|---:|---|---:|---:|
| v1 | 423 | 1735 | 603 | 247 MB (full) | 133 098 | 3 |
| v1b | 407 | 1735 | 543 | 216 MB (full) | 134 305 | 3 |
| A6 | 265 | 1612 | 427 | 229 MB (full) | 214 429 | 3 |
| E04 | 392 | 1700 | 371 | 49 MB (partial) | 228 643 | 3 |
| MIN | 252 | 1735 | 305 | 79 MB (partial) | 267 680 | 2 |

`churn.txt` starts at 77 MB indexes for MIN, 160 MB for E04, 277 MB for v1b. Per-variant timings of the S9 queries: `<variant>/S9-R2/timings.csv`. Churn time falls with fewer indexes (A6 265 s, MIN 252 s vs 407 s for v1b, -38 %) but this is **a single run per variant** and the spread of `churn_seconds` was not measured; the order of magnitude is consistent with the index count (3 vs 6). The partial indexes grow from 1.6 to 49-79 MB because entries of completed rows stay until autovacuum cleans the index; the sizes depend on the autovacuum timing (MIN ran one autovacuum fewer, 267 k dead tuples at the end), so index-size and dead-tuple comparisons between variants after churn are not reliable. The partial record-key index stays 2.7x-4x smaller than the full one (49/79 MB vs 216-247 MB) even so. Table size is variant independent (1700-1735 MB; the 1612 MB of A6 follows autovacuum timing).

## Verdict

### E04 (partial record-key index only): **win**
- P1 reads: no difference in S8-S2, S8-S4 and all full-ownership labels (keys_strict/keys_nonstrict/pages, table above; plans use the partial record-key index with the same or fewer buffers: keys_strict 32 vs 42 buffers). The `pages` +52 % on S2 is a plan-mode flip (M vs C, flagged `?`), not an index effect (S8-S2: -3 %).
- P1 writes: no latency change; WAL/update -7 %; storage -116 MB (-42 % of indexes), churn index growth 49 MB vs 216 MB.
- P2: no difference (the status/partition indexes are kept).
- P3 regression: `delete_by_key_status` (no usable record-key index for `WHERE record_key = ? AND status = ?` when `status` is the only other index key): S8-S4 0.12 -> 413 ms, S5 8.7 -> 96.7 ms (`E04/S5-R2/timings.csv`), S2 0.11 -> 0.35 ms. With `idx_status` available on S2/S8-S2 the planner filters 1199 rows; on S4/S6 (1 M NEW) it filters a million. P3 only, does not block.
- Portability: partial index = PostgreSQL and SQL Server (filtered index); not MySQL, MariaDB, Oracle (Oracle could use a function-based index on `CASE WHEN completed_at IS NULL THEN record_key END`), H2 (no). On those dialects the `(record_key, completed_at, created_at, id)` form stays.

### MIN: **mixed - P1 equal and cheaper writes, P2 regressions; not acceptable as it was measured**
- P1 reads and writes: equal to v1b (all HOT cells within noise; S8-S2 keys_strict 0.88 vs 1.08 ms is below the 30 % rule), WAL/write -9 % insert and -16 % upd_complete, 3 indexes / 77 MB instead of 6 / 277 MB. Concurrency (S10 p4): 1204 rec/s mean vs 1054 (v1b), inside the pass-to-pass noise.
- P2 loses where the dropped indexes were the access path:
  - `oldest_due_strict`: 0.61 -> 186 ms (S8-S2), 0.71 -> 446 ms (S8-S4), 190 ms (S2), 721 ms (S4). Cause: this query has no override in MIN (A6 list), so it lacks `o.completed_at IS NULL` and the partial queue index cannot serve it; with `idx_status_retry` dropped the planner seq-scans the table. Likely fixable by adding the literal (to be measured; the queue index orders by created_at, so a `next_retry_at` order still needs a sort over the owned slice).
  - `oldest_due_nonstrict`: 0.17 -> 1.00 ms (S8-S2, `?` mode flip), 0.25 -> 146 ms (S8-S4): the partial index is ordered by `created_at`, so `MIN(next_retry_at)` reads the whole due slice. Not fixable by a literal.
  - `count_partitions` / `count_statuses`: cost grows with the number of incomplete rows: S8-S2 +0.12 / +0.62 ms (the second above the 0.15 ms and 20 % limits), S8-S4 16 -> 122 ms and 38 -> 119 ms, S4 65 -> 119 / 38 -> 122 ms. Both read the whole partial index (the `INCLUDE` columns make it wide).
- With a normal backlog (S2: a few thousand incomplete rows) the regression is 0.1-0.6 ms per refresh except for `oldest_due_strict`; under a 1 M backlog (S4/S8-S4) it is 80-450 ms per refresh.

### Explicit answer: is MIN a good v2 candidate?
Not as measured. E04 is a clear step (drop the 118 MB record-key index, keep reads). The further step to MIN saves another ~85 MB of indexes and ~9-16 % WAL per write, but the new observability collector (`count_statuses`, `count_partitions`, `oldest_due_*`) is then served by a created_at-ordered index. After adding the missing literal to `oldest_due_strict` (not yet measured) MIN would remain a candidate if (a) `oldest_due_nonstrict` +0.8 ms (S8-S2) / +146 ms under a 1 M backlog and the counts becoming O(incomplete rows) are accepted for P2, or (b) one of `idx_status_retry` / `idx_partition_status_retry` is kept (A5/A2 reports in `E05-drop-indexes.md` decide which). Recommended next measurement: MIN + literal on `oldest_due_strict` + `idx_outbox_record_status_retry` kept.

## What MIN loses

| lost | effect | source |
|---|---|---|
| `COMPLETED` counts: `count_completed` (`status = COMPLETED`, no literal, not in MIN's index) | seq scan: 29.8 -> 104.7 ms (S8-S2), 29.6 -> 192.9 ms (S8-S4), 24.4 -> 104.9 (S7); P2 non-target (not collected by default) | `MIN/S8-S2-R2/timings.csv` and plans `count_completed.txt` (Parallel Seq Scan) |
| `count_statuses` over all three statuses (`count_statuses_all`) | 40.3 -> 139.6 ms (S8-S2), 71.8 -> 319 ms (S8-S4), parallel seq scan; E04 unchanged 40.7 / 71.6 ms | same |
| `delete_by_key_status` (record_key + status) | 0.27 -> 164.9 ms (S8-S2), 0.12 -> 279.5 (S8-S4); seq scan (`plans/delete_by_key_status.txt`, 200 ms; E04 gets 413 ms on S4 and uses `idx_status` + filter) | P3 |
| retention scans of FAILED: `find_failed_before` 0.27 -> 5.9 ms, `ids_failed_before` 0.23 -> 1.3 ms, `delete_by_status_failed` 12.2 -> 198 ms (S8-S2); S7 `find_failed_before` 3.2 -> 223 ms, `ids_failed_before` 0.67 -> 53.4 ms | the A6 override with the literal makes them use the created_at-ordered partial index, not `(status, next_retry_at)` | P3 (rank 3) |
| COMPLETED retention (`find_/ids_completed_before`) | no index in any variant: ~500 ms (S2/S8-S2) in v1/v1b/E04; MIN is 40-50 % faster in S8-S2/S2/S5/S6 (251-274 vs 500 ms) and unchanged in S7. The cause was not checked in the plans; marked `?` in the result tables | P3 |
| index for `idx_partition_status_retry` access (`count_partition_single`, deprecated metrics) | +0.21 ms S8-S2, +156 ms S8-S4 | out of scope per user rule |

## What breaks if `count_statuses` is called with COMPLETED (`record-statuses`)

`OutboxMetricsProperties.recordStatuses` (`namastack-outbox-observability/.../OutboxMetricsProperties.kt`) defaults to `{NEW, FAILED}` but may include COMPLETED (test `OutboxInstanceMetricsMeterBinderTest.kt:365`). The MIN override of `count_statuses` is `SELECT status, COUNT(*) ... WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status` (`experiments/R2-MIN/queries/count_statuses.sql`). With COMPLETED in the list:
1. **Wrong result, silently**: COMPLETED rows have `completed_at IS NOT NULL`, so no COMPLETED group is returned and the gauge reports 0 / missing, while 986 000 rows exist (`MIN/S9-R2/churn.txt`, before). No error, no warning. The equivalence run does not catch it (its `count_statuses` uses NEW/FAILED).
2. A correct COMPLETED count needs a statement without the literal (like `count_statuses_all`, the v1 form), which on MIN is a parallel seq scan: 139.6 ms (S8-S2), 318.99 ms (S8-S4) vs 40 / 72 ms with `idx_status` (`E04/S8-S2-R2`, `E04/S8-S4-R2`). A two-statement design (NEW/FAILED with the literal, COMPLETED separately without it) fixes correctness but each refresh then pays the seq scan.
3. The invariant `completed_at IS NULL <=> status IN (NEW, FAILED)` must hold in the data (documented at `DefaultJdbcOutboxRecordQueries.kt:14`); with it the NEW/FAILED form is exact.

## Caveats

- **Single runs**: every SQL-level cell, `writecost` of E04/A6/v1b-run1, S9 and the S4 write cells are single measurements (S10 has two passes; v1, v1b, MIN have two `writecost` runs).
- **Suspect cells** (`?`): any cell where `plancache.csv` mixes C/M/G between compared variants, e.g. S2 `pages` E04/MIN vs v1b, `count_failed` S4/S8-S4 (0.25 vs 22 ms identical plan family), `oldest_due_nonstrict`, `count_completed`. The mode letter is shown in each table cell for the key ones.
- The S4 write medians (E04 insert 0.86, upd_retry 0.75; MIN upd_retry 0.65 vs 0.27 ms) have identical plans across variants and are not reproduced by `writecost`; treated as noise/outliers, not as an index effect.
- S10 pages p50 is bimodal (about 1 ms vs 13-20 ms) in 11 of 16 runs of the `IN` form (v1b, A6, E04, MIN) and 0 of 4 of v1's `unnest` form; cause unverified; relevant for the `pages` choice in `R2-v1b.md`.
- S9 index sizes after churn depend on when autovacuum ran (MIN: 2 autovacuums, others 3).
- The MIN `oldest_due_strict` regression is an override gap, not a property of the index set; its fix was not measured.
- PostgreSQL only; other dialects need the phase 6 measurements (no partial indexes on MySQL/MariaDB/Oracle/H2).

## Follow-up ideas

1. MIN + `AND o.completed_at IS NULL` in `oldest_due_strict` (`R2-MIN2`), with and without `idx_outbox_record_status_retry`.
2. Narrow the partial queue index (`INCLUDE` only `partition_no, next_retry_at`; `record_key` is the widest column) to cut `count_*` buffers (17 388 in S8-S4) and write cost; test a partial `(partition_no, next_retry_at) WHERE completed_at IS NULL AND status = 'NEW'`-style index only if a literal can be sent.
3. Measure S10 `pages` plan mode (custom vs generic) to explain the 1 ms / 15 ms bimodality before ranking by throughput.
4. Re-measure the S4 write cells with `writecost` on S4 data to settle the E04 insert/upd_retry outliers.
