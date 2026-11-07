# MySQL check: do the MariaDB form results carry over? (2026-11-07)

## Setup
- MySQL `mysql:9.2` (9.2.0, image sha in `results/*/mysql/*/env.txt`), harness lane 1 (port 13406, CPU-pinned), **default server settings**: `innodb_buffer_pool_size` = 128 MiB, `eq_range_index_dive_limit` = 200, binlog on, collation `utf8mb4_0900_ai_ci`, container 4 CPU / 4 GB.
- Code/DDL: `mdb-opt2` = `4037faa5`, branch `schema/mysql/outbox-tables.sql` (status indexes without `completed_at`, 4th index `(status, partition_no)`), catalog `mdb-opt2/mysql`. Ids UUIDv7 (`HARNESS_ID_VERSION=7`, snapshot marker `ids=uuidv7` checked before/after every cell, `ids.txt` in every tree).
- A = catalog (branch forms), B = catalog + `forms-b/` (five overlays; MIN forms `maxrows: none`, no `:limit`; `keys_*` NOT EXISTS texts from `DefaultJdbcOutboxRecordQueries` at `4037faa5`). Per label: gen once, then A, B, A2 (A again, drift check) back to back, groups `HOT,MON`, 5+20 reps, 60 s limit per query. Host load 0.2-1.8 throughout (no outside load; STATE.md's relaxed rule not needed). Thresholds: HOT 11 %, MON 10 % / 0.04 ms.
- Harness changes (README section "MySQL 9.2 use"): `DIALECT` env in `run-cells.sh`, InnoDB parts of `bench` enabled for MySQL, history-list fallback. Smoke test (gen + prepare + bench S1, equiv A vs A on S1) passed before the run.
- Run notes: the disk filled once (MySQL binlogs, ~28 GB); I ran `bench dropruns` and `PURGE BINARY LOGS`, then re-ran the driver (resumable). S7 B and A2 therefore ran on a regenerated (identical, seed 42) S7 snapshot; A2 is within noise of A everywhere (<= 6 %).
- **Limits of the comparison:** 128 MiB buffer pool (MariaDB harness: also default, but the S4/S5 indexes do not fit here; `primed 0-2 index scans`, see `env.txt`), so large-scenario numbers contain buffer-pool misses (`handlers.csv` bp_reads). The DDL has no `completed_at` in the status indexes (MariaDB final design had), so the "ordered index serves the whole predicate" argument is weaker than there. Plans are `EXPLAIN ANALYZE` text; the MariaDB `SQL_SELECT_LIMIT` plan prefix does not exist (all five statements carry `LIMIT` in SQL or none).

## Equivalence (B vs A, v7, `HARNESS_SAMPLE_ORDERED=1`)
S1: 28 PASS, 2 SKIP (unbounded finds); S5: 26 PASS, 2 SKIP. 0 WARN / FAIL: the five query ids PASS (same keys in the same order; MIN value equals the ordered value). Files: `results/equiv/mysql/{S1,S5}/my-A-vs-my-B.md`. (`results/failures.txt` "EQUIV FAIL" lines are a driver grep false positive on the test name `upd_fail`.)

## Timings (median ms; B/A with verdict; A2 = A again)
`>60 s` = stopped at the limit. Verdict: B better / A better / = (within threshold).

| label | query | A | B | A2 | B/A | verdict |
|---|---|---|---|---|---|---|
| S8-S2 | oldest_created | 1.26 | 1.26 | 1.28 | 1.00 | = |
| S8-S2 | oldest_due_nonstrict | 0.40 | 0.63 | 0.42 | 1.56 | A better |
| S8-S2 | oldest_due_strict | 1.39 | 2.51 | 1.39 | 1.81 | A better |
| S8-S2 | keys_strict | 3.00 | 1.81 | 2.99 | 0.60 | B better |
| S8-S2 | keys_nonstrict | 3.52 | 1.84 | 3.53 | 0.52 | B better |
| S8-S4 | oldest_created | 0.45 | 5799 | 0.42 | 12 900 | A better |
| S8-S4 | oldest_due_nonstrict | 0.49 | 197 | 0.46 | 404 | A better |
| S8-S4 | oldest_due_strict | 5569 | 10856 | 5595 | 1.95 | A better |
| S8-S4 | keys_strict | 0.89 | 0.90 | 0.88 | 1.01 | = |
| S8-S4 | keys_nonstrict | 0.90 | 0.92 | 0.90 | 1.03 | = |
| S16-S4 | oldest_created | 0.26 | 4580 | 0.25 | 17 500 | A better |
| S16-S4 | oldest_due_nonstrict | 0.31 | 4569 | 0.32 | 14 900 | A better |
| S16-S4 | oldest_due_strict | 5610 | 8571 | | 1.53 | A better |
| S16-S4 | keys_strict / nonstrict | 0.72 / 0.72 | 0.76 / 0.76 | | 1.06 / 1.07 | = |
| S8-S5 | oldest_created | 0.39 | 1713 | 0.38 | 4350 | A better |
| S8-S5 | oldest_due_nonstrict | 0.41 | 1716 | 0.42 | 4160 | A better |
| S8-S5 | oldest_due_strict | 1322 | 1890 | 1299 | 1.43 | A better |
| S8-S5 | keys_strict | **31 840** | **227** | 33 188 | 0.007 | B better |
| S8-S5 | keys_nonstrict | 1594 | 1002 | 1615 | 0.63 | B better |
| S4 | oldest_created | 0.46 | > 60 s | 0.44 | | A better |
| S4 | oldest_due_nonstrict | 0.46 | > 60 s | 0.48 | | A better |
| S4 | oldest_due_strict | 5597 | > 60 s | 5550 | | A better |
| S4 | keys_strict / nonstrict | 1.09 / 1.05 | 1.18 / 1.08 | 1.12 / 1.05 | 1.08 / 1.03 | = |
| S5 | oldest_created | 0.44 | 1502 | 0.46 | 3380 | A better |
| S5 | oldest_due_nonstrict | 0.42 | 49 | 0.41 | 118 | A better |
| S5 | oldest_due_strict | 1269 | 1043 | 1307 | 0.82 | B better (A2 within 3 % of A) |
| S5 | keys_strict | 14.1 | 8.4 | 14.1 | 0.60 | B better |
| S5 | keys_nonstrict | 920 | 971 | 972 | 1.06 | = (A2/A also 1.06) |
| S7 | oldest_created | 0.39 | 2.29 | 0.38 | 5.9 | A better |
| S7 | oldest_due_nonstrict | 0.44 | 3.03 | 0.43 | 6.8 | A better |
| S7 | oldest_due_strict | 0.59 | 52 | 0.58 | 89 | A better |
| S7 | keys_strict / nonstrict | 1.04 / 0.96 | 1.12 / 1.02 | 1.01 / 1.00 | 1.08 / 1.06 | = |

Full per-query tables: `results/my-{A,B,A2}/mysql/<label>/timings.csv`; plans `plans/<id>.txt`, rows examined `handlers.csv`.

## Plans (EXPLAIN ANALYZE; rows examined from `handlers.csv`)
- `oldest_created` / `oldest_due_nonstrict`, A: `idx_outbox_record_status_created_id` (covering lookup, partition filter) / `idx_outbox_record_status_retry_partition` (covering range); `LIMIT 1` stops after 1-15 rows (S8-S4 handler reads: 1 key + 3 next). B (MIN): no early stop. MIN reads the whole status (or due) range, e.g. S8-S4 `oldest_created` ~1e6 index rows with partition filter via index condition, S16-S4 `oldest_due_nonstrict` 62 536 rows (range scan on `idx_outbox_record_status_partition`, then filter), 4.6-5.8 s. S7 (few NEW rows) is cheap for both.
- `oldest_due_strict`: both forms nested-loop antijoin over the outer index and probe `idx_outbox_record_key_completed_created_id` per outer row; A's `LIMIT 1` over an outer scan in `next_retry_at` order stops at the first accepted row but at S4/S5 the first accepted row is far in (1e6 outer rows, 5.6 s); MIN is never better (visits all 250 k candidates, 10.9 s).
- `keys_*`, A (head form): outer range/lookup on `idx_outbox_record_status_created_id` in `(created_at,id)` order (stops early on the outer side); the dependent subquery `Select #2` reads **all** unfinished rows of the key (`rows=2000 loops=n`, "Sort ... limit input to 1 row(s)" on top of a ref lookup `record_key, completed_at = NULL`): MySQL does not use the index order of `(record_key, completed_at, created_at, id)` for the `ORDER BY ... LIMIT 1` (ref on `IS NULL`), so each head lookup is O(2000). At hot keys with many non-head outer rows this multiplies (S8-S5 `keys_strict`: 31.8 s). B (`NOT EXISTS`): antijoin probing the same index with `earlier.created_at <= o.created_at`; early exit per outer row; S8-S5 `keys_strict` examines 34 k outer rows, 218 k next-reads, 227 ms. `keys_nonstrict`: both forms scan the key's ~2000 NEW rows per outer row at S5 (A 92 ms/loop, B 97 ms/loop), 1-1.6 s either way.

## Does the MariaDB result carry over?
1. **`ORDER BY ... LIMIT 1` beats MIN for `oldest_created` / `oldest_due_nonstrict`: YES, carries over** (S8-S4 0.45 vs 5799 ms, S16-S4 0.26 vs 4580 ms, S8-S5/S5 ~4000x; equal at S8-S2 `oldest_created`; S7 5-7x). Same mechanism: MIN cannot stop early when `partition_no` is not the next index column after the MIN column. Only deviation: S8-S2 `oldest_due_nonstrict` A is also better (0.40 vs 0.63 ms), S5 `oldest_due_nonstrict` MIN is 49 ms (not seconds) but still 118x slower.
2. **`oldest_due_strict`: ordered `NOT EXISTS` form kept: YES, carries over** (no better form among the two; MIN is worse at S8-S4/S16-S4/S4/S8-S5/S7, only S5 shows B 18 % faster, single non-repeated cell where A and A2 agree). Both forms are slow on MySQL at S4/S5 (seconds); this check does not search for a better form.
3. **Head form beats `NOT EXISTS` for `keys_*` at hot keys: NO, it reverses on MySQL 9.2** with this DDL. `NOT EXISTS` is better or equal everywhere: S8-S5 `keys_strict` 31.8 s -> 227 ms (MariaDB 107 -> 54 ms went the other way), `keys_nonstrict` 1.6 s -> 1.0 s (MariaDB 4.9 -> 0.6 ms for head), S5 `keys_strict` 14.1 -> 8.4 ms, S8-S2 both keys 1.7-1.9x faster for `NOT EXISTS`; equal at S8-S4, S16-S4, S4, S7 and `keys_nonstrict` at S5. The MariaDB head-form advantage rests on the subquery stopping at the first index entry in `(created_at, id)` order; MySQL's optimizer reads all unfinished rows of the key and sorts them. Also not reproduced: MariaDB's "seconds -> sub-ms" for S5/S7 (MySQL `keys_nonstrict` at S5 stays near 1 s for both forms). Caveat: 128 MiB buffer pool and no `completed_at` in the status indexes; neither explains the head form's per-key full read, but absolute numbers at S4/S5 include buffer-pool misses.

Verdict: (1) and (2) carry over; (3) does not (MySQL prefers `NOT EXISTS` for `keys_strict`; `keys_nonstrict` is a draw or mildly `NOT EXISTS`). A MySQL-specific `MySqlJdbcOutboxRecordQueries` `keys_*` form would need a confirm run (more labels, two passes) before changing code.
