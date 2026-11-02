# E1P - is the PostgreSQL `findByKeysAndStatus` override necessary?

> **Orchestrator note after round 2:** this verdict covers the isolated bench only. In the S10 processing loop the `IN` form has `pages` p50 of 11–21 ms in 11 of 16 runs, while the `unnest` form stays at 0.6–1.3 ms in 8 of 8 (`results/<variant>/postgres/S10*-R2/concurrency-*.txt`). The cause is not verified. Do not drop the override until this is explained; see `STATE.md` "Open findings after round 2".

## Hypothesis, base, change

Hypothesis: the base-class `record_key IN (...)` query is as good as the PostgreSQL `unnest` + `CROSS JOIN LATERAL` override, so the override can be dropped.
Base: v1 (worktree `outbox-wt-v1`, sha e0b09b41). Query-only change, no DDL change, no branch. Override file: `experiments/E1P-pages-default-pg/queries/pages.sql`.

v1 (override, `harness/catalog/v1/postgres/pages.sql`):
```sql
SELECT o.* FROM ( SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k ) k
CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status
  AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id ) o
```
E1P (base class):
```sql
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
ORDER BY record_key, completed_at, created_at, id
```
Priority: P1 (hot path). Noise: HOT 30 % and > 0.15 ms absolute (`STATE.md`, `results/noise-postgres.md`).

## Results (median / p95 ms, E1P relative to v1; `pages` is the only query that differs, `keys_strict` is a control)

Sources: `results/v1/postgres/E1P-<label>-R1/timings.csv` vs `results/E1P/postgres/<label>-R1/timings.csv`, via `harness/bin/compare.py` (negative = E1P faster).

| label | keys | rows | v1 median | E1P median | change | v1 p95 | E1P p95 | change | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| S2 | 10 | 10 | 0.776 | 0.657 | -15 % | 1.060 | 0.943 | -11 % | within noise (0.12 ms) |
| S2-b100 | 100 | 100 | 2.145 | 1.783 | -17 % | 4.280 | 3.792 | -11 % | within noise (0.36 ms, but < 30 %) |
| S4 | 10 | 10 | 0.718 | 0.777 | +8 % | 1.084 | 1.034 | -5 % | noise |
| S4-b100 | 100 | 100 | 1.934 | 1.828 | -5 % | 4.830 | 3.736 | -23 % | noise |
| S4-b1000 | 1000 | 1000 | 14.018 | 9.889 | -29 % | 16.883 | 11.401 | -32 % | E1P faster by 4.1 ms; -29 % is at the threshold, p95 -32 % exceeds it |
| S5 (2 000 rows/key) | 10 | 20000 | 136.2 | 95.3 | -30 % | 151.3 | 128.8 | -15 % | E1P faster (see plan note) |
| S7 | 10 | 10 | 0.802 | 0.725 | -10 % | 1.255 | 1.218 | ~0 | noise |
| S8-S2 (owned mod4) | 10 | 10 | 0.690 | 0.633 | -8 % | 1.080 | 0.936 | -13 % | noise |

Control `keys_strict` (unchanged query): S2 2.212/2.174, S2-b100 2.641/2.544, S4 1.191/1.186, S4-b100 1.610/1.580, S4-b1000 5.265/5.371, S5 1.282/1.364, S7 13.278/12.845, S8-S2 2.002/2.025 (v1/E1P). All within 0-6 %, so the pair is measured consistently (same files as above).

No label shows E1P worse than v1 beyond noise. Small-batch labels show E1P 5-17 % faster, below the HOT threshold.

## Plans

Files: `results/{v1/postgres/E1P-<label>-R1,E1P/postgres/<label>-R1}/plans/pages.{txt,json}`.

Does PostgreSQL serve the IN list with an ordered index scan and no Sort? Yes, for every one of the 10/100/1000-key cases (S2, S4-b100, S4-b1000, S7): E1P uses a single `Index Scan using idx_outbox_record_record_key_completed_created_id` with `Index Cond: record_key = ANY ('{...}') ` (and `completed_at IS NULL` as index condition), `Filter: status = 'NEW'`, and no Sort node. PostgreSQL 18 skip-scan/multi-array index searches return rows in index order (record_key, completed_at, created_at, id), satisfying the ORDER BY. No bitmap scan.

| case | v1 plan | E1P plan | buffers v1 / E1P | exec ms (EXPLAIN) v1 / E1P |
|---|---|---|---|---|
| S2, 10 keys | Sort(unnest) + Nested Loop + Index Scan x10 | Index Scan, 9 index searches | 50 / 48 | 0.091 / 0.059 |
| S4-b100, 100 keys | same, 100 loops, 100 searches | Index Scan, 98 searches | 500 / 487 | 0.527 / 0.459 |
| S4-b1000, 1000 keys | same, 1000 searches | Index Scan, 968 searches | 5000 / 4870 | 5.733 / 4.811 |
| S7, 10 keys | same | Index Scan | 50 / 46 | 0.103 / 0.093 |

Scaling 10 -> 100 -> 1000 keys: both forms scale linearly with the same shape (about 5 buffers per key, one index search per key); the override additionally pays a Sort of the unnest keys (1.2 ms at 1000 keys) and a nested-loop step; the planner row estimates for E1P are poor (`rows=1` at 10 keys, `rows=758` at 1000) but harmless because the single index scan is the only sensible path. Planning time grows with key count in both (0.07-0.11 ms at 10-100, 0.76 ms E1P vs 0.53 ms v1 at 1000).

S5 (2 000 rows per key, 20 000 rows, `plans/pages.txt`): both forms must sort; neither uses the ordered index scan.
- v1: per key, Bitmap Heap Scan (BitmapAnd of the record-key index and `idx_outbox_record_status`, 199 980 status entries scanned per key) + Sort `completed_at, created_at, id`, 20915 buffers, 91.5 ms.
- E1P: one Parallel Bitmap Heap Scan (same BitmapAnd) + Sort `record_key, completed_at, created_at, id` (external merge on disk, 7.7 MB/worker) + Gather Merge with 2 workers, 14054 buffers, temp 2070 read/2073 written, 54.2 ms.
So the `IN` form wins here only because the planner can parallelise it; this is a planner choice that depends on `max_parallel_workers_per_gather` and the host, not a property of the form. The status-index scan dominates both. Page size of 2 000 per key is outside production fetch sizes.

## Equivalence

`results/equiv/postgres/S2/E1P-vs-v1.md`: pages PASS (10 vs 10 rows over 10 keys, per-key id order equal); keys_strict/keys_nonstrict PASS.
`results/equiv/postgres/S4/E1P-vs-v1.md` (batch 100): pages PASS (100 vs 100 rows over 100 keys, per-key order equal).
`results/equiv/postgres/S5/E1P-vs-v1.md`: pages PASS (20000 vs 20000 rows over 10 keys, per-key id order equal).
Cross-key order: the IN form orders by `record_key`; the override orders by `t.k` (same collation in the same database), and the per-key order is identical. The caller groups by key, so only per-key order matters.

## Driver statement cache

The IN list is expanded to one placeholder per key, so each key count (1..1000) is a distinct SQL string and a distinct entry in pgjdbc's statement cache (default `preparedStatementCacheQueries` 256, `preparedStatementCacheSizeMiB` 5). pgjdbc switches a statement to server-side prepare after `prepareThreshold` (5) executions; for the IN form this happens per key count, and with variable batch sizes in production (1..`MAX_RECORD_KEYS_PER_FETCH`) the cache can churn and some counts never reach the threshold. The ARRAY form is a single statement text for all key counts (only the array literal expansion `ARRAY[?,?,...]` in `:recordKeys` differs here in the harness; with a real bound array it would be one statement), so it benefits fully from the server-prepared cache. Caveat for timings: after five executions pgjdbc may use a generic plan (`STATE.md` Phase 2 caveat); the generic plan for `= ANY($1..$n)` keeps the same ordered index scan, so no plan flip is expected, but it was not measured here (no `plan_cache_mode=force_generic_plan` run in E1P). The benefit of a single statement is real but not measured as a latency difference.

## Verdict: no difference (override not necessary for performance)

At the production-relevant sizes (10-1000 keys, one or a few rows per key) E1P is equal or faster than v1 on every label (range -17 % to +8 %, all within noise except S4-b1000 at the threshold where E1P is faster), the plan is a single ordered index scan without Sort, buffers are equal or lower, results are equivalent. The `unnest` + `LATERAL` override does not buy anything measurable on PostgreSQL 18, and it adds a PostgreSQL-only code path. Recommendation: drop the override (use the base-class query) unless the statement-cache argument (fixed statement text) is valued above uniformity. Caveats: this is PG 18 (index searches over `= ANY` array in index order is a feature of the btree scan, older versions also do this for `= ANY` and sorted arrays, but the evidence here is only PG 18); with many rows per key (S5) both forms sort and bitmap-scan, and the difference there is parallelism.

## Portability

- IN list with ORDER BY is portable (MySQL, MariaDB, SQL Server, Oracle, H2); Oracle limits an IN list to 1000 literals (`MAX_RECORD_KEYS_PER_FETCH` = 1000 is exactly at the limit), SQL Server limits parameters to 2100 per statement.
- `unnest` / `LATERAL` is PostgreSQL-specific (`CROSS APPLY` on SQL Server, `LATERAL` on MySQL 8.0.14+/Oracle 12c+, no H2 equivalent for array unnest in this form), so dropping it removes the only dialect-specific `pages` query.

## Follow-up ideas

- Measure under `plan_cache_mode = force_generic_plan` and with a bound `text[]` parameter (`= ANY(?)` via `setArray`) as a third form: one statement text, no unnest, no Sort.
- Run a variable-batch workload to quantify statement-cache churn of the IN form (not covered by the fixed-size batches here).
- Check S5-like page sizes with a lower `max_parallel_workers_per_gather` to confirm the 30 % gain is parallelism only.
