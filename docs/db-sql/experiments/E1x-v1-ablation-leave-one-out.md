# E1x - v1 leave-one-out ablation (PostgreSQL 18.6, round 1)

Experiments E11, E12, E13, E14, E1Q, E1M (spec: `docs/db-sql/experiments/round1-spec.md`). Base = `v1`. Each variant reverts exactly one v1 item
(or one disjoint set of query ids for E1Q); the reference is the `v1` run of the same snapshot.

Paths below are relative to `docs/db-sql/results/`. "T" = `<variant>/postgres/<label>-R1/timings.csv` (median ms, 20 reps, 5 warmup),
"P" = `<variant>/postgres/<label>-R1/plans/<query>.json|.txt`, "W" = `<variant>/postgres/S2-R1/writecost.csv`.

## How to read the verdicts

- `matters`: reverting the item makes P1/P2 worse (or costs space/WAL for nothing); keep the item.
- `no difference`: within noise on P1/P2 and no plan regression.
- `harmful`: reverting the item improves P1/P2.
- Noise rule (STATE.md): a difference counts only if it exceeds the group threshold (HOT/WRITE 30 %, MON/RET/ADMIN 20 %) AND 0.15 ms.
- Priorities: P1 = `keys_*`, `pages`, `insert`, `upd_*`, `delete_by_id`, S10. P2 = `count_statuses`, `count_partitions`, `oldest_*`. P3 = retention/admin (reported only).
- Round-1 correctness: `bin/bench equiv` of E11, E12, E13, E14, E1Q vs v1 on S2 and S5, and E1M vs v1 on S2: 26 PASS, 0 FAIL, 0 ERROR each
  (`equiv/postgres/S2/E1{1,2,3,4,Q,M}-vs-v1.md`, `equiv/postgres/S5/E1{1,2,3,4,Q}-vs-v1.md`).

### Caveat: the v1 reference is a noisy control for some cells

Variants with an identical code path give very different medians in some cells, so a flag is trusted only when it is backed by a plan change or by several scenarios:

- S4 write path: `insert` / `upd_complete` of v1 = 0.590 / 0.385 ms, while E14 (a statistics object cannot touch writes) = 0.843 / 0.896, E11 1.054 / 1.354, E12 0.900 / 0.969, E13 0.862 / 1.025, main 0.910 / 1.325 (T S4). All "S4 write slower" flags in the E11-E14 diffs are therefore noise of the v1 reference (the S4 variant order put v1 late: `logs/round1-pg.log`, line "label S4").
- `count_failed` S4 / S8-S4 is bimodal (0.17-0.28 ms vs 21.7 ms; v1, main, E12, E13 = 21.7; E11, E14, E1Q = 0.2) and `count_new` S6 (v1, main = 21.3; all others 7.4-7.7). Same query, same DDL, so this is table state (heap fetches of an index-only scan), not a variant effect.
- S2 `keys_strict` with an identical plan: v1 2.14, E12 2.25, E11 3.39 (min 2.22), E13 1.61, E14 4.32 (T S2). Spread up to about 50 % for the same plan shape; in S8-S2 (same data, 64 partitions) v1 / E11 / E12 / E13 / E14 = 2.20 / 2.24 / 1.98 / 1.75 / 1.74.

## Summary

| Rank | Item | Prio | Verdict | Key numbers | Add-one (E2x) in round 2 |
|---:|---|---|---|---|---|
| 1 | V1-4 `keys_strict` rewrite (E1Q) | P1 | matters | S4 4 084 -> 1.25 ms, S5 14 493 -> 1.29, S6 174 -> 1.41, S7 53.6 -> 13.3, S2 4.06 -> 2.14 | yes (on main DDL and on main + V1-2 index) |
| 2 | V1-2 partial incomplete-record index (E13) | P1/P2 | matters | S4 keys 1.25 -> 808 ms, S5 1.29 -> 144, S6 1.41 -> 110, S10 p4 1110 -> 659 rec/s; write cost about 0 (WAL -2.9 % insert); S7 keys faster without it (13.3 -> 8.7) | superseded by E01/E02 (status-leading variants) |
| 3 | V1-5 `keys_nonstrict` rewrite (E1Q) | P1 | matters, with a regression in small-NEW cases | S4 5 889 -> 1.27, S5 116 -> 1.24, S6 110 -> 1.19; but S8-S2 0.92 -> 2.01 (v1 slower), S7 8.8 -> 12.8 | yes (together with V1-2 variants; fix the FAILED-skipping scan) |
| 4 | V1-3 `CREATE STATISTICS` (E14) | P1 | matters | S2 keys_strict 2.14 -> 4.32 ms (+101 %) and keys_nonstrict 2.12 -> 4.68 (+121 %) without it; S9 +56 / +71 %; S10 p4 keys p50 7.2 -> 18.1 ms, 1110 -> 770 rec/s; cost: ANALYZE +8.3 % (416 -> 450 ms) | yes (main + statistics) |
| 5 | V1-8 pages `unnest` + `LATERAL` + `completed_at IS NULL` (E1Q) | P1 | matters (moderate) | S5 134 -> 191 ms (+42 %), S7 0.94 -> 1.60 (+71 %), S8-S2 0.66 -> 0.98 (+49 %); S2/S4/S6 within noise | yes (see also E1P) |
| 6 | V1-1b `id` appended to the record-key index (E12) | P1 | no difference (time); costs +46 MB | no query changes by more than noise; plan: index-only -> index scan probes, pages gets `Incremental Sort`; index 77.4 MB (no id, main) vs 123.6 MB (with id) | yes (drop-`id` / E05-style ablation on main DDL, with WAL) |
| 7 | V1-1a drop `(record_key, created_at)` (E11) | P1 writes | matters (space/WAL only, latency no difference) | re-adding it: +67.3 MB index (+23 % index bytes), WAL/insert +2.2 % (1 thread) / +2.0 % (4 threads), WAL/`upd_complete` +3.8 %; no read gain on P1/P2 | no (confirm in the E05 drop series) |
| 8 | V1-7 `oldest_due_strict` ORDER BY + LIMIT (E1Q) | P2 | matters | S4 575 -> 0.95 ms, S5 5 172 -> 1.00, S6 87 -> 1.21, S7 34 -> 0.87, S2 3.33 -> 0.95, S8-S4 435 -> 0.74 | yes (needs V1-2/predecessor `<=`) |
| 9 | V1-6 `oldest_created` ORDER BY + LIMIT (E1Q) | P2 | matters | S4 214 -> 0.65, S5 63.7 -> 0.64, S6 85.6 -> 0.54, S7 4.1 -> 1.47, S2 1.20 -> 0.84 | yes (depends on the created_at-ordered index) |
| 10 | V1-10 `countByStatuses` (one GROUP BY) | P2 | no difference vs two single counts (not faster); saves round trips | single query vs `count_new + count_failed`: S2 1.28 vs 1.20 ms, S5 18.8 vs 7.7 (+11 ms), S4 38.5 vs 51.3 | no |
| 11 | V1-11 collector: no `count_completed` per refresh | P2 | matters | removes 8.9-29.6 ms per refresh; per refresh 30.1 -> 1.28 ms (S2), 48.3 -> 21.5 (S6) | no |
| 12 | V1-9 SQL row limit on retention find/id queries (E1Q) | P3 | matters (P3, reported only) | `find_completed_before` S4 7 496 -> 495 ms, S2 1 532 -> 497; `find_failed_before` S7 684 -> 3.0; `ids_failed_before` S7 267 -> 0.7 | no |
| - | V1-0 query-class infra | - | not measured (no SQL change by itself) | - | - |
| - | E1M `findOldestDueRetry` ORDER BY vs MIN | P2 | keep MIN | ORDER BY slower by 0.19-0.50 ms (+42 % to +78 %) in all 7 labels | optional: re-test with generic plan |

Round-2 add-one list (E2x): V1-4 and V1-5 (query rewrite on main DDL, with and without a V1-2-style index); V1-6 and V1-7 (same pairing; they need an ordered index on `created_at` / `next_retry_at`); V1-3 (main + statistics, and v1-without-V1-2 + statistics); V1-1b (main with `id` appended); V1-8 (main + pages form, alongside E1P). Not needed: V1-1a (pure drop, covered by the drop series), V1-9, V1-10, V1-11, V1-2 (the status-leading / partial family E01/E02 replaces the add-one).

---

## V1-4 `keys_strict` rewrite (no GROUP BY / MIN, `completed_at IS NULL`, `<=` predecessor, ORDER BY + LIMIT) - matters (P1)

Run: E1Q = v1 with main's `keys_strict.sql` (T `E1Q/postgres/<label>-R1/timings.csv` vs `v1/postgres/<label>-R1/`). Main for orientation.

| Label | v1 | E1Q (main form) | main | change E1Q vs v1 |
|---|---:|---:|---:|---|
| S2 | 2.142 | 4.057 | 5.905 | +89 %, +1.9 ms |
| S8-S2 | 2.200 | 2.032 | 2.022 | within noise |
| S4 | 1.246 | 4 083.9 | 4 411.0 | x3 277 |
| S8-S4 | 0.994 | 2 726.3 | 2 853.5 | x2 742 |
| S5 | 1.289 | 14 492.7 | 14 675.8 | x11 243 |
| S6 | 1.412 | 173.9 | 411.3 | x123 |
| S7 | 13.336 | 53.637 | 55.912 | +302 % |

Plan (P `E1Q/postgres/S4-R1/plans/keys_strict.txt` vs `v1/postgres/S4-R1/plans/keys_strict.txt`): v1 `Limit > Nested Loop Anti Join > Index Only Scan idx_outbox_record_created_id_incomplete (10 rows, stops early) > Index Only Scan record_key index`; main form `Sort > Aggregate > Sort > Hash Join > Seq Scan + Index Scan idx_status` (whole NEW set, 333k rows per worker), so cost grows with the backlog. S2: E1Q hash-joins the 14 000-entry incomplete index with the 1 000 NEW rows (3.9 ms), v1 stops after 10.
Write cost: none (query only). Verdict: **matters**.

## V1-2 partial incomplete-record index `idx_outbox_record_created_id_incomplete` (E13 = v1 without it) - matters (P1, P2)

DDL: v1 minus `idx_outbox_record_created_id_incomplete` (`experiments/E13-ablate-v1-2-no-queue-index/ddl.sql`).

| Label | query | v1 | E13 | change |
|---|---|---:|---:|---|
| S4 | keys_strict | 1.246 | 808.179 | x649 |
| S4 | keys_nonstrict | 1.274 | 806.390 | x633 |
| S4 | oldest_created | 0.651 | 471.436 | x724 |
| S8-S4 | keys_strict / keys_nonstrict / oldest_created | 0.994 / 0.988 / 0.384 | 519.6 / 522.9 / 456.0 | x500+ |
| S5 | keys_strict / keys_nonstrict / oldest_created | 1.289 / 1.237 / 0.635 | 143.5 / 143.3 / 93.4 | x110 / x116 / x147 |
| S6 | keys_strict / keys_nonstrict / oldest_created | 1.412 / 1.188 / 0.538 | 110.0 / 110.0 / 117.7 | x78 / x93 / x219 |
| S7 | keys_strict / keys_nonstrict | 13.336 / 12.780 | 8.689 / 8.397 | -35 % / -34 % (E13 faster) |
| S7 | oldest_created | 1.467 | 3.882 | +165 % |
| S2 | keys_strict / keys_nonstrict | 2.142 / 2.116 | 1.609 / 2.080 | -25 % / -2 % (n.s.) |
| S8-S2 | oldest_created | 1.173 | 0.552 | -53 % (E13 faster) |

(all T rows from `E13/postgres/<label>-R1/timings.csv` and `v1/postgres/<label>-R1/timings.csv`.)

Plans: without the index PostgreSQL falls back to `Nested Loop > Sort > Index Scan idx_outbox_record_status` (all NEW rows read and sorted: S4 `Gather Merge > Sort` 333 333 rows x 3 workers, exec 324 ms; `E13/postgres/S4-R1/plans/keys_strict.txt`). `oldest_created` becomes `Sort > Index Scan idx_status` (471 ms). In S7 (390 000 FAILED, 10 000 NEW) v1 walks 133 903 FAILED entries of the status-agnostic partial index before it finds 10 NEW keys (`v1/postgres/S7-R1/plans/keys_strict.json`: Rows Removed by Filter 133 903), whereas E13 uses `idx_status` selectively, hence E13 faster there (see V1-5 / E02).

S10 concurrency (4 pollers, 4 producers, 60 s, single run each): v1 1109.8 processed/s, keys p50 7.22 / p95 15.89 ms, update p50 2.00, insert p50 1.90 (`v1/postgres/S10-R1/concurrency-p4-r0.txt`); E13 658.7 processed/s, keys p50 32.09 / p95 94.28 ms (`E13/postgres/S10-R1/concurrency-p4-r0.txt`). 1 poller: v1 287.9/s keys p50 6.91; E13 150.5/s keys p50 40.42 (`.../concurrency-p1-r0.txt`). E13 insert/update p50 are lower (1.47-1.55 vs 1.9-2.0 ms) because the pollers hold fewer cycles; this is a throughput artefact, not a write-cost gain.

Write cost (S2, W `v1/postgres/S2-R1/writecost.csv` vs `E13/postgres/S2-R1/writecost.csv`; committed, one run each):

| metric | main | v1 | E13 (v1 - V1-2 index) |
|---|---:|---:|---:|
| index count (incl. pkey) | 6 | 6 | 5 |
| incomplete index bytes before / after 15 000 writes | - | 1 949 696 / 3 653 632 | - |
| indexes total before / after | 309.0 / 312.6 MB | 289.9 / 294.9 MB | 287.9 / 291.4 MB |
| insert 1 thread p50 / p95 ms | 2.095 / 2.602 | 1.885 / 2.538 | 2.022 / 2.581 |
| WAL bytes / insert (1 thread) | 6 466 | 6 674 | 6 478 |
| WAL bytes / insert (4 threads) | 6 531 | 6 851 | 6 437 |
| `upd_complete` committed p50 ms | 2.165 | 2.187 | 2.087 |
| WAL bytes / `upd_complete` | 7 579 | 7 481 | 7 499 |

The index is 1.9-3.7 MB on a 1M-row table (only incomplete rows), +0.1 ms-level latency, WAL/insert +3.0 % (1 thread) / +6.4 % (4 threads) vs E13 and none for updates. Verdict: **matters** (large P1/P2 win, negligible write cost). The residual S7 and S8-S2 gaps point to a status-leading variant (E02).

## V1-5 `keys_nonstrict` rewrite - matters, with a regression in small-NEW tables (P1)

Run: E1Q with main's `keys_nonstrict.sql` (T `E1Q/...` vs `v1/...`).

| Label | v1 | E1Q (main form) | main | change E1Q vs v1 |
|---|---:|---:|---:|---|
| S2 | 2.116 | 1.510 | 1.646 | -29 % (-0.6 ms; just below 30 %, n.s.) |
| S8-S2 | 2.007 | 0.924 | 0.910 | -54 % (-1.1 ms): v1 slower |
| S4 | 1.274 | 5 889.3 | 1 352.2 | x4 622 |
| S8-S4 | 0.988 | 1 473.2 | 1 440.5 | x1 491 |
| S5 | 1.237 | 115.9 | 115.9 | x94 |
| S6 | 1.188 | 110.2 | 110.5 | x93 |
| S7 | 12.780 | 8.816 | 8.636 | -31 % (-4.0 ms): v1 slower |

Plans: with a big NEW backlog the main form aggregates all NEW rows (`Sort > Aggregate > Index Scan idx_status`, S5 99 980 rows per worker), the v1 form stops after 10. With a small NEW set and many FAILED rows the v1 form walks the status-agnostic partial index past FAILED entries: S8-S2 `Index Only Scan idx_outbox_record_created_id_incomplete ... Rows Removed by Filter 12 832`, 1.24 ms (`v1/postgres/S8-S2-R1/plans/keys_nonstrict.txt`), versus the main form `Aggregate > Index Scan idx_status` over 229 NEW rows, 0.37 ms (`E1Q/postgres/S8-S2-R1/plans/keys_nonstrict.txt`). S7: removed 133 903 vs 10 000 NEW read.
Verdict: **matters** (removes the multi-second collapse in S4/S5/S6), but the v1 rewrite has a P1 regression of 1.1-4 ms in S8-S2 / S7. Follow-up: status-leading queue index (E02) so the early-stop scan sees only NEW.

## V1-3 `CREATE STATISTICS ... (mcv) ON status, completed_at` (E14) - matters (P1)

See "E14 details" below.

## V1-8 pages: `unnest` + `CROSS JOIN LATERAL`, `completed_at IS NULL`, ORDER BY (E1Q with main's `pages.sql`) - matters (moderate, P1)

| Label | v1 | E1Q (main pages) | main | change |
|---|---:|---:|---:|---|
| S2 | 0.709 | 0.905 | 0.961 | +28 % (+0.20 ms, below 30 %: n.s.) |
| S8-S2 | 0.661 | 0.983 | 0.841 | +49 % (+0.32 ms) |
| S4 | 0.860 | 0.897 | 0.909 | n.s. |
| S8-S4 | 0.672 | 0.784 | 0.943 | +17 % n.s. |
| S5 | 133.946 | 190.666 | 145.831 | +42 % (+56.7 ms) |
| S6 | 0.939 | 0.902 | 0.794 | n.s. |
| S7 | 0.935 | 1.598 | 1.611 | +71 % (+0.66 ms) |

Plans: S2/S7 main form = `Sort > Bitmap Heap Scan > BitmapAnd(idx_status, record_key index)` over all NEW rows of the keys (est 1, act 10; `E1Q/postgres/S7-R1/plans/pages.txt`, bitmap index scan on idx_status reads 10 000 entries, 0.78 ms) against v1 `Nested Loop > Sort > Function Scan > Index Scan record_key index` per key (0.0 ms probe, `v1/postgres/S7-R1/plans/pages.txt`). Note: the `E1Q` (and main) form shows the `BitmapAnd` plan only in the scenarios where it wins the cost race; E1P covers the separate question of the default `IN (...)` form.
Verdict: **matters** (consistent in S5 / S7 / S8-S2; none in S2 / S4 / S6).

## V1-1b `id` appended to `(record_key, completed_at, created_at)` (E12) - no difference (time), +46 MB

DDL: `(record_key, completed_at, created_at)` instead of `(..., id)` (`experiments/E12-ablate-v1-1b-no-id/ddl.sql`).

P1/P2 T diff vs v1 (flags only; all `E12/postgres/<label>-R1/timings.csv`): none for `keys_*`, `pages`, `oldest_*`, `count_*` in any label. Flagged cells: `insert` / `upd_complete` S4 (v1 reference outlier, see caveat), `oldest_created` S5 0.635 -> 0.982 (plan identical, the query does not use this index: noise), P3 `find_incomplete_by_key` S8-S2 -58 % faster (P3).
Selected P1 values v1 / E12: keys_strict S2 2.142 / 2.246, S4 1.246 / 1.570 (+26 %, n.s.), S5 1.289 / 1.272, S6 1.412 / 1.516, S7 13.336 / 13.327; pages S2 0.709 / 0.761, S4 0.860 / 0.751, S5 133.9 / 134.7.
Plan changes (P `E12/.../keys_strict.json`, `pages.json`): the predecessor probe changes from `Index Only Scan` to `Index Scan` (heap visit; act 0.0 rows in both cases), `pages` gets an `Incremental Sort` above the index scan because the index no longer delivers `id` order (`E12/postgres/S2-R1/plans/pages.txt`).
Index size: no `writecost` run for E12 (not in the spec). The no-`id` index is main's `idx_outbox_record_record_key_completed_created`: 77.4 MB before / 78.7 MB after the S2 write test (`main/postgres/S2-R1/writecost.csv`) vs 123.6 / 125.7 MB with `id` (`v1/postgres/S2-R1/writecost.csv`): **+46.2 MB (+60 %)** for the `id`.
Verdict: **no difference** on latency in this data (1 row per key at most for S2/S4, pages never need a sort of more than a few rows); the `id` costs +46 MB per 1M rows and has no measured benefit; sorted-by-`id` pages matter only for keys with many equal `created_at`/many rows (S5 uses a bitmap + Sort in both variants). Candidate for removal; needs the write-cost number (add-one / drop run in round 2).

## V1-1a drop `(record_key, created_at)` (E11 = v1 + that index re-added) - matters (space and WAL only)

DDL: v1 + `CREATE INDEX idx_outbox_record_record_key_created ON outbox_record (record_key, created_at)`.

Read effect on P1/P2: none. Plans use it (`keys_nonstrict` S4 / S8-S4 predecessor probe, `find_incomplete_by_key`, `delete_by_key_status`) but times equal: keys_nonstrict S4 1.274 -> 1.240; pages plan unchanged (v1 index chosen), 0.709 -> 0.716 (S2), 0.860 -> 0.739 (S4), 133.9 -> 135.8 (S5) (T `E11/...`). Flags: `keys_strict` S2 +58 % (plan identical, min 2.217 ms = v1 median; S8-S2 2.244 vs 2.200: noise), S4 write latency (v1 outlier), P3 `delete_by_key_status` S7 0.104 -> 0.916 (E11 plan picks the record-key/created index, `E11/postgres/S7-R1/plans/delete_by_key_status.txt`), `find_failed_before` S2 (P3).

Write cost (W `E11/postgres/S2-R1/writecost.csv` vs `v1/...`):

| metric | v1 | E11 | delta |
|---|---:|---:|---|
| index count | 6 | 7 | +1 |
| indexes total after writes | 294.9 MB | 363.4 MB | +68.5 MB (+23 %) |
| `idx_outbox_record_record_key_created` | - | 67.3 -> 68.4 MB | |
| insert 1 thread p50 | 1.885 | 2.107 | +12 % (+0.22 ms, below 30 %: n.s.) |
| WAL / insert (1 thread) | 6 674 | 6 824 | +2.2 % |
| WAL / insert (4 threads) | 6 851 | 6 991 | +2.0 % |
| `upd_complete` p50 | 2.187 | 2.229 | +2 % |
| WAL / `upd_complete` | 7 481 | 7 763 | +3.8 % |

(Main DDL carries both record-key indexes: 6 indexes total, 309.0 MB.) Verdict: **matters** for storage and WAL (+67 MB, +2-4 % WAL per write), no read benefit and no measurable write latency effect; the drop is justified by the decision rule.

## V1-7 `oldest_due_strict` ORDER BY + LIMIT 1 and `<=` predecessor (E1Q) - matters (P2)

| Label | v1 | E1Q (main form) | main |
|---|---:|---:|---:|
| S2 | 0.947 | 3.328 | 4.069 |
| S8-S2 | 0.695 | 1.571 | 1.508 |
| S4 | 0.951 | 575.280 | 697.161 |
| S8-S4 | 0.737 | 434.727 | 847.950 |
| S5 | 0.998 | 5 172.332 | 5 176.157 |
| S6 | 1.208 | 87.356 | 165.015 |
| S7 | 0.869 | 34.001 | 34.440 |

Plans: v1 `Limit > Nested Loop > Index Scan idx_status_retry > Index Only Scan record-key index` (first match, 1 row); main form aggregates all due NEW rows. Verdict: **matters**; all differences exceed MON 20 % and 0.15 ms.

## V1-6 `oldest_created` ORDER BY + LIMIT 1 + `completed_at IS NULL` (E1Q) - matters (P2)

| Label | v1 | E1Q (main form) | main |
|---|---:|---:|---:|
| S2 | 0.836 | 1.195 | 1.168 |
| S8-S2 | 1.173 | 0.735 | 0.690 |
| S4 | 0.651 | 214.012 | 214.858 |
| S8-S4 | 0.384 | 210.429 | 211.397 |
| S5 | 0.635 | 63.654 | 63.610 |
| S6 | 0.538 | 85.555 | 85.501 |
| S7 | 1.467 | 4.146 | 4.378 |

Plan: v1 `Limit > Index Only Scan idx_outbox_record_created_id_incomplete` (1 row); main `Aggregate > Index Scan idx_status` over all NEW rows (S2 E1Q: 1 200 rows, `E1Q/postgres/S2-R1/plans/oldest_created.txt`). S8-S2 v1 is slower (1.173 vs 0.735) for the same FAILED-skipping reason as V1-5. Depends on V1-2 (E13 loses the benefit, see above). Verdict: **matters**.

## V1-9 SQL row limit on `find_*_before` / `ids_*_before` (E1Q) - matters (P3, reported only)

| Label | query | v1 | E1Q | main |
|---|---|---:|---:|---:|
| S2 | find_completed_before | 496.7 | 1 531.8 | 1 578.7 |
| S4 | find_completed_before | 495.5 | 7 496.1 | 1 571.5 |
| S4 | find_failed_before | 0.230 | 1.572 | 0.214 |
| S6 | find_completed_before | 397.8 | 1 239.8 | 1 240.8 |
| S7 | find_completed_before | 251.2 | 1 031.8 | 1 173.2 |
| S7 | find_failed_before | 2.978 | 683.7 | 680.0 |
| S7 | ids_failed_before | 0.715 | 267.1 | 271.2 |
| S7 | ids_completed_before | 248.5 | 313.2 | 312.8 |

(`E1Q/...`, `v1/...`, `main/...` T.) No index serves these queries in either variant (v1 `find_completed_before` still about 0.25-0.5 s on 1M rows; P3, not a target). Verdict: **matters** (P3); no cost.

## V1-10 `countByStatuses` and V1-11 collector change (computed from results, no run)

Source: T `v1/postgres/<label>-R1/timings.csv`, `main/...` (same files as above). Medians in ms. "3 counts" = main's collector (`count_new + count_failed + count_completed`); "2 counts" = NEW + FAILED as separate queries; "countByStatuses" = `count_statuses` on v1.

| Label | count_new | count_failed | count_completed | 3 counts (main) | 2 counts (main) | `count_statuses` (v1) |
|---|---:|---:|---:|---:|---:|---:|
| S2 | 0.339 | 0.796 | 28.960 | 30.10 | 1.14 | 1.283 |
| S8-S2 | 0.300 | 0.732 | 28.966 | 30.00 | 1.03 | 1.247 |
| S4 | 29.484 | 21.855 (0.19 low mode) | 29.637 | 80.98 (59.3) | 51.34 (29.7) | 38.450 |
| S8-S4 | 29.339 | 0.170 | 29.668 | 59.18 | 29.51 | 38.570 |
| S5 | 7.600 | 0.186 | 8.899 | 16.69 | 7.79 | 18.754 |
| S6 | 21.204 (7.5 low) | 0.286 | 26.834 | 48.32 | 21.49 (7.8) | 21.451 |
| S7 | 0.714 | 22.242 | 24.085 | 47.04 | 22.96 | 27.044 |

(low-mode values are from the other v1-DDL variants, see caveat; ranges for all v1-DDL variants: `count_statuses` stays within +-3 % in every label.)

- **V1-10** (one `GROUP BY status` query instead of separate counts): not faster than two single counts. It is equal in S2/S8-S2 (+0.1 ms), cheaper than the high-mode two counts in S4, but slower in S5 (18.75 vs 7.79 ms, +11 ms, parallel GroupAggregate over 200 000 entries, `v1/postgres/S5-R1/plans/count_statuses.txt`) and S8-S4 (+9 ms vs low-mode `count_failed`), S7 (+4 ms). All are P2 on a 15 s refresh. The gain is one round trip; verdict **no difference** (absolute cost is dominated by the NEW-count scan). Plan: `Aggregate > Index Only Scan idx_status` with `Heap Fetches` 0-241; estimates 0.95-1.27x of actual, same with and without statistics.
- **V1-11** (no `count_completed` per refresh + one statuses query): per refresh 30.10 -> 1.283 ms (S2, -96 %), 30.00 -> 1.247 (S8-S2), 80.98 -> 38.45 (S4, -52 %), 59.18 -> 38.57 (S8-S4, -35 %), 16.69 -> 18.75 (S5, +12 %, n.s.), 48.32 -> 21.45 (S6, -56 %), 47.04 -> 27.04 (S7, -43 %). Removed `count_completed` alone is 8.9-29.6 ms per refresh (v1 29.069, 28.921, 29.299, 29.428, 11.128, 26.937, 24.151 for S2, S8-S2, S4, S8-S4, S5, S6, S7). Verdict: **matters** (P2). `count_partitions` and the deprecated per-partition count are unchanged (0.85 / 64.3 / 13.8 / 21.0 / 1.97 ms in S2 / S4 / S5 / S6 / S7, same in all variants).

## V1-0 query-class infrastructure

No SQL change by itself; not measured. Equivalence of every variant is PASS (see top).

---

## E14 details: extended statistics (`outbox_record_status_completed_stx`, E14 = v1 without it)

DDL: `experiments/E14-ablate-v1-3-no-statistics/ddl.sql`. Verdict: **matters** (P1): without the statistics object the planner misses the correlation `status = 'NEW'` AND `completed_at IS NULL`, underestimates the matching rows by two orders of magnitude when NEW is a tiny share of a big table, and picks a worse plan for the hot key query.

### Estimated vs actual rows

Estimates are the planner `Plan Rows` of the driving scan on the (partial) incomplete index for `keys_strict` / `keys_nonstrict` / `oldest_created` (P `<variant>/postgres/<label>-R1/plans/*.json`). The scan is cut short by `LIMIT`, so "actual" is the scenario truth (due NEW rows, `02-scenarios.md`; S2 1 000 and S9 1 000 due NEW confirmed by `E14/postgres/S2-R1/plans/keys_strict.json`, actual 1 000 from `idx_status`). Factor = est / actual (<1 underestimate).

| Label | true due NEW | v1 est | v1 factor | E14 est | E14 factor | plan shape v1 -> E14 |
|---|---:|---:|---:|---:|---:|---|
| S2 | 1 000 | 1 199 | 1.2x | 11 | **0.011 (91x under)** | `Limit > NL > IOS created_id_incomplete` -> `Limit > Sort > NL > Index Scan idx_status` |
| S8-S2 | about 250 | 208 | 0.8x | 5 | 0.02 (50x under) | same change |
| S9 (after churn, autoanalyze only) | 1 000 | 583 | 0.6x | 28 | **0.028 (36x under)** | same change; `oldest_created`: `Limit > IOS` -> `Sort > Index Scan idx_status` |
| S4 | 1 000 000 | 640 929 | 0.64x | 313 441 | 0.31x (3.2x under) | unchanged |
| S5 | about 200 000 | 126 202 | 0.63x | 51 370 | 0.26x (3.9x under) | unchanged |
| S6 | 5 000 (195 000 NEW future) | 163 287 | 33x over | 31 204 | 6.2x over | unchanged |
| S7 | 10 000 | 6 770 | 0.68x | 3 878 | 0.39x (2.6x under) | unchanged |

Sources: `keys_strict`: `v1/postgres/S2-R1/plans/keys_strict.json` vs `E14/postgres/S2-R1/plans/keys_strict.json` (and the same path for S8-S2, S4, S8-S4, S5, S6, S7, `S9-R1`). `keys_nonstrict`, `oldest_created`, `oldest_due_strict` show the same factors (e.g. `oldest_created` S2 est 1 201 vs 11). `oldest_due_strict` (driving `idx_status_retry`, no stats-dependent choice): v1 / E14 est S2 1 199 / 738, S9 583 / 1 063, S4 640 963 / 629 938; shape unchanged in all labels.
Non-limit nodes (direct est vs actual):
- `pages` S5: Sort est v1 2 004 / E14 809 vs actual 2 000 per key, nested loop 20 040 / 8 090 vs 20 000 (E14 2.5x under, same plan); S2 / S4 / S9 est 10 vs 10, 1 vs 1 (identical).
- `count_statuses` (status only, no correlation): est v1 / E14 S2 13 240 / 14 836 vs 14 000; S4 422 057 / 414 797 vs 333 333 per loop; S5 117 915 / 118 363 vs 100 000; S9 24 420 / 22 981 vs 24 027. Within 0.95-1.27x with or without the object.

### Timings (T `E14/...` vs `v1/...`)

| Label | query | v1 | E14 | change | plan |
|---|---|---:|---:|---|---|
| S2 | keys_strict | 2.142 | 4.316 | **+101 %** (+2.2 ms) | Sort plan |
| S2 | keys_nonstrict | 2.116 | 4.683 | **+121 %** (+2.6 ms) | Sort plan |
| S8-S2 | keys_strict / keys_nonstrict | 2.200 / 2.007 | 1.736 / 2.127 | n.s. (only 229 NEW rows to sort) | Sort plan |
| S9 | keys_strict | 3.015 | 4.698 | **+56 %** (+1.7 ms) | Sort plan |
| S9 | keys_nonstrict | 2.639 | 4.520 | **+71 %** (+1.9 ms) | Sort plan |
| S9 | oldest_created | 0.802 | 0.866 | n.s. | `Sort > Index Scan idx_status` (1 200 rows) |
| S4, S8-S4, S5, S6, S7 | keys_* | 0.97-13.3 | 0.98-13.1 | within noise | unchanged |
| S4 / S5 / S7 | oldest_due_strict | 0.951 / 0.998 / 0.869 | 1.215 / 1.338 / 1.172 | +28 % / +34 % / +35 % (+0.26-0.34 ms) | identical plan; S6 shows -22 %: noise |
| all | pages, count_statuses | | | n.s. | unchanged |

S10 (4 pollers, 4 producers, 60 s, single run each; `v1/postgres/S10-R1/concurrency-p4-r0.txt`, `E14/postgres/S10-R1/concurrency-p4-r0.txt`): processed 1 109.8 -> 770.1 rec/s (-31 %), keys query p50 7.22 -> 18.07 ms (x2.5), p95 15.89 -> 88.67 ms, pages p50 0.77 -> 0.31 ms (noise), update p50 2.00 -> 1.55, insert p50 1.90 -> 1.50 (inserted 1 629 -> 2 043 per s: the faster producers are a consequence of slower pollers).
S9 plan (`E14/postgres/S9-R1/plans/keys_strict.json`): `Sort > Nested Loop > Index Scan idx_status` (est 28, actual 1 000) versus v1 `Limit > NL > IOS created_id_incomplete` (est 583).

### Generic plans (`--pg-plan-cache-mode force_generic_plan`, S2, `v1|E14/postgres/S2-generic-R1`)

| query | v1 median | E14 median | generic plan v1 | generic plan E14 |
|---|---:|---:|---|---|
| keys_strict | 1.936 | 1.789 | `Limit > NL Anti Join > IOS created_id_incomplete` (est 1 415) | same plan (est 1 653) |
| pages | 0.638 | 0.907 | `NL > Sort > Function Scan > Index Scan` | same plan |

With `status = $1` the MCV list of the statistics object is not used, so both variants plan the same way and the difference vanishes (keys_strict generic 1.936 vs 1.789 ms; pages +0.27 ms is within the pages run-to-run spread, plan identical; custom-plan pages S2 0.709 / 0.662). The custom-plan E14 Sort plan is the one that appears in the main matrix (S2 4.3 ms), so in a driver that stays on custom plans (pgjdbc keeps server-side custom plans for the first executions and then adopts the generic plan only when it is not costlier) the statistics matter; with force-generic they do not. Plans: `E14/postgres/S2-generic-R1/plans/keys_strict.generic.txt`.

### ANALYZE cost (S2, `bin/bench analyze --n 5`)

| | median | runs |
|---|---:|---|
| v1 (with statistics, 1 object) | 450.5 ms | 463.3 444.2 445.9 460.3 450.5 (`v1/postgres/S2-analyze-R1/analyze.txt`) |
| E14 (none) | 416.0 ms | 433.3 416.0 415.0 417.2 415.7 (`E14/postgres/S2-analyze-R1/analyze.txt`) |

+34.5 ms (+8.3 %) per ANALYZE on 1M rows; no write-path cost (the object only affects ANALYZE / planning). Note the statistics object is PostgreSQL-only DDL.

Verdict: **matters**. The plan or timing changes above the noise threshold in S2 (+101 / +121 %), S9 (+56 / +71 %) and S10 (processed rate -31 %). Round 2: add-one on main (does main + statistics change anything on the main queries / E01 indexes?) and verify that the benefit also holds when the partial index is replaced by a status-leading one (E02).

---

## E1M `findOldestDueRetry`: `MIN` (v1) vs `ORDER BY next_retry_at LIMIT` - keep MIN

Query: v1 `SELECT MIN(o.next_retry_at) ... status = :status AND next_retry_at <= :now`; E1M `SELECT o.next_retry_at ... ORDER BY o.next_retry_at LIMIT :limit` (`experiments/E1M-oldest-due-order-by/queries/oldest_due_nonstrict.sql`). Back-to-back runs on each snapshot with `--only oldest_due_nonstrict`; v1 reference in `v1/postgres/E1M-<label>-R1/timings.csv`, E1M in `E1M/postgres/<label>-R1/timings.csv`. Equivalence E1M vs v1 S2: PASS (`equiv/postgres/S2/E1M-vs-v1.md`).

| Label | MIN (v1) median / p95 / min | ORDER BY (E1M) median / p95 / min | change | beyond noise (20 % and 0.15 ms) |
|---|---|---|---|---|
| S2 | 0.616 / 1.148 / 0.544 | 1.033 / 1.474 / 0.913 | +68 %, +0.42 ms | yes |
| S8-S2 | 0.403 / 0.893 / 0.311 | 0.647 / 0.764 / 0.555 | +61 %, +0.24 ms | yes |
| S4 | 0.632 / 1.014 / 0.577 | 0.902 / 1.035 / 0.840 | +43 %, +0.27 ms | yes |
| S8-S4 | 0.460 / 0.870 / 0.393 | 0.652 / 1.011 / 0.564 | +42 %, +0.19 ms | yes |
| S5 | 0.638 / 1.278 / 0.608 | 1.137 / 1.364 / 0.958 | +78 %, +0.50 ms | yes |
| S6 | 0.602 / 1.082 / 0.548 | 0.955 / 1.132 / 0.878 | +59 %, +0.35 ms | yes |
| S7 | 0.673 / 1.132 / 0.597 | 0.968 / 1.405 / 0.892 | +44 %, +0.30 ms | yes |

Plans (P `v1/postgres/E1M-<label>-R1/plans/oldest_due_nonstrict.json`, `E1M/postgres/<label>-R1/plans/oldest_due_nonstrict.json`): PostgreSQL rewrites `MIN` to the same early-terminating index probe: v1 `Result > Limit > Index Scan idx_outbox_record_status_retry` (S2 exec 0.052 ms, S7 0.092), E1M `Limit > Index Scan idx_outbox_record_status_retry` (S2 0.054 ms, S7 0.058); 1 row read, buffers hit 4 in both (S5). So the server-side plan is identical (no plan regression), yet the measured client-side latency is higher in all 7 labels, and the minimum of E1M exceeds the median of MIN in S2 (0.913 vs 0.616) and S5 (0.958 vs 0.638). EXPLAIN uses custom plans with literal parameters; the timing loop runs 5 warmups + 20 reps with a server-prepared statement and a bound `LIMIT ?`, so the difference is plausibly the generic-plan/`LIMIT $n` handling (not verified; no generic-plan EXPLAIN was captured for E1M).

**Verdict (user rule: switch only if within noise everywhere and no plan regression): keep MIN.** The ORDER BY form is slower by 0.19-0.50 ms in every scenario, above both the MON threshold and 0.15 ms. Re-check on the other dialects in phase 6 as STATE.md requires; optional follow-up: capture the generic-plan EXPLAIN of E1M and re-time with `plan_cache_mode = force_custom_plan` to see whether the gap is a driver/plan-cache effect. V1-6/V1-7 (ORDER BY + LIMIT) are not affected: their gain is in the plan (early stop vs aggregation of all NEW rows).

---

## Round 2 (add-one) shortlist

1. V1-4 / V1-5 / V1-6 / V1-7 on main DDL, alone and with a V1-2-style index (needs the index to stop early; E01/E02 give the index family).
2. V1-3 statistics on main and on v1-without-V1-2.
3. V1-1b: main + `id` in the record-key index (and the write-cost measurement that E12 lacks).
4. V1-8: main + pages form (with E1P result).
5. V1-5 follow-up: queue index that is status-leading or has `status` filtered, so `keys_nonstrict` and `oldest_created` stop at NEW only (S7, S8-S2 regressions); E02a/b/c measure candidates.
6. Not needed: V1-1a, V1-2 (superseded by E01/E02), V1-9, V1-10, V1-11.
