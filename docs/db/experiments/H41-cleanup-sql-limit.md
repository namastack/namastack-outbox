# H41: real SQL `LIMIT` instead of `.withMaxRows(limit)` for the four cleanup selects
Status: measured
Group: G5 (cleanup). Iteration 1. Best combined with H40.
Target queries: `findCompletedRecords(completedBefore, limit)`, `findFailedRecords(lastRetryBefore, limit)`, and the
id selects of `deleteCompletedRecords` / `deleteFailedRecords`.

## Change

Rewritten SQL (Postgres; `:limit` is a bind parameter, `.withMaxRows(limit)` is removed):

```sql
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit;
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit;
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit;
SELECT id FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at LIMIT :limit;
```

Code diff (description only; `src/` is not changed by this investigation), `JdbcOutboxRecordRepository`:

```diff
-        ORDER BY completed_at
+        ORDER BY completed_at
+        ${limitClause(":limit")}
 ...
-            .withMaxRows(limit)
+            .param("limit", limit)
```

where `limitClause` is chosen per database (see Portability); the JDBC module already detects the database
(`config/JdbcDatabaseType`, today used only for schema initialisation).

Index sets: base (no index for COMPLETED) and H40 add.

## Rationale / expected plan

`withMaxRows` is invisible to the planner and makes the server run the portal without parallel workers
(`01-baseline.md`, section 5.4). With a SQL limit:

- base, COMPLETED: `Limit -> Gather Merge -> Sort (top-N heapsort, in memory) -> Parallel Seq Scan`. The scan still
  reads the whole heap, but the sort keeps only `limit` rows (no spill) and the workers run because pgjdbc no
  longer sends a row limit. Expected: 157-273 ms (serial, spill) -> **~40-80 ms** for limit 1000 (parallel seq
  scan of 90.9k pages dominates; the parallel baseline without sort spill was 82-87 ms for the id select).
- H40 index, COMPLETED: `Limit -> Index Scan idx_..._status_completed` at **every** cutoff (the planner now knows
  it needs only `limit` rows of an ordered path): expected **~0.5-2 ms** for limit 1000, independent of cutoff
  selectivity (cutpct 0.1-1.0). This closes the selectivity risk of H40 alone.
- FAILED: `Limit -> Index Scan idx_..._status_retry` (already ordered by `next_retry_at`): unchanged, ~0.1 ms.
- Generic plan (`LIMIT $3`): the planner assumes a 10% fraction for an unknown limit; with the H40 index the
  ordered index path still wins. Measured with `--generic`.

## Semantic equivalence argument

`withMaxRows(n)` returns the first n rows of the ordered result; `ORDER BY ... LIMIT n` returns the first n rows
of the same ordered result. Identical except inside ties of the sort key at the n-th row, where both forms leave
the choice to the plan (contract: "sorted by completion time ascending ... at most limit records", no tie order).
Seeds A-E have unique `completed_at` / FAILED `next_retry_at`, so the comparison must PASS exactly.
Check per scenario and cutpct (0.1, 0.9) with the reference files that reproduce `withMaxRows` semantics
(cursor + `FETCH :limit`, planned like the unnamed portal):

```
compare-results.sh <c> experiments/H41/eq/orig_findCompletedRecordsBefore_maxrows.sql experiments/H41/queries/findCompletedRecordsBefore.sql --var cutpct=0.9
compare-results.sh <c> experiments/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql experiments/H41/queries/deleteCompletedRecords_selectIds.sql --var cutpct=0.1
compare-results.sh <c> experiments/H41/eq/orig_findFailedRecordsBefore_maxrows.sql experiments/H41/queries/findFailedRecordsBefore.sql
compare-results.sh <c> experiments/H41/eq/orig_deleteFailedRecords_selectIds_maxrows.sql experiments/H41/queries/deleteFailedRecords_selectIds.sql
```

also with `--var limit=1` and `--var limit=100000` (limit larger than the result), and on `seed-edge`.

## Portability

The repository has one SQL text for all dialects today, so the limit needs a per-dialect clause:
- Postgres, MySQL, MariaDB, H2: `LIMIT ?`.
- Postgres, Oracle 12c+, H2, MariaDB 10.6+: `FETCH FIRST ? ROWS ONLY`. MySQL does not support `FETCH FIRST`.
- SQL Server 2012+: `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` (requires ORDER BY, present) or `SELECT TOP (?) ...`.
So two forms cover everything: `LIMIT ?` (Postgres, MySQL, MariaDB, H2) and `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY`
(SQL Server, Oracle 12c+, also valid on Postgres/H2). Choose via `JdbcDatabaseType`.
- JPA already uses `setMaxResults(limit)`, which Hibernate renders as a real SQL limit for each dialect: JPA is
  already in the H41 state. Mongo uses `.limit(limit)`: same.

## Experiment plan

1. Scenarios A, B, D; index sets base and H40 add (one container each).
2. `run-explain.sh -q experiments/H41/queries` custom (as executed: no `withMaxRows`), `--generic`, `--costs`,
   cutpct 0.1 / 0.5 / 0.9 / 1.0, limits 100 / 1000 / 10000 via `--var limit=...`.
3. Compare with the H40 cursor emulation (index without SQL limit) and the baseline `-serial` rows.
4. Equivalence as above. No regression run (query-only change; the H40 index has its own regression run).
5. Accept if base drops >= 50% and H40+H41 is < 5 ms at every cutoff.

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

Real SQL `LIMIT` replaces `.withMaxRows(limit)`; limit 1000 unless noted; custom plans are the as-executed cost (no row limit on the portal => parallel workers allowed). Details/tables are shared with H40; main numbers:

| query | scen / cutoff | baseline (withMaxRows, as executed) | H41 without index (H08a ctl) | H41 + `(status,completed_at)` add | + H07 index |
|---|---|---|---|---|---|
| findCompletedRecordsBefore | A 10% | 155-280 (serial, spill) | 79.8 (-49% vs 155; parallel top-N heapsort, 90,939 buffers) | **0.18** | 0.19 |
| | A 90% | 385-1,415 | 132 | 0.14 | 0.20 |
| | B 10% | 152-217 | 76.9 | 0.17 | 0.17 |
| | B 90% | 339-1,157 | 119.6 | 0.14 | 0.17 |
| deleteCompletedRecords_selectIds | A 10% / 90% | 170 / 511 (serial EXPLAIN) | 79.0 / 120.5 | 0.21 / 0.20 | 0.22 / 0.21 |
| | B 10% / 90% | 161 / 429 | 78.4 / 106.5 | 0.27 / 0.24 | 0.22 / 0.22 |
| findCompletedRecordsBefore generic (`LIMIT $3`) | A 10% / 90% | - | 106.7 / 142.8 (idx_status plan) | 0.16 / 0.30 | 0.23 / 0.22 |
| | B 10% / 90% | - | 86.6 / 115.5 | 0.15 / 0.17 | 0.21 / 0.41 |
| findFailedRecordsBefore / deleteFailedRecords_selectIds | A/B | 0.11 / 0.08 | 0.14 / 0.08 (`idx_status` + sort) | same | same |

Findings:
- Without an index the real LIMIT turns `Gather Merge -> external Sort -> Parallel Seq Scan` into a top-N sort (no spill) on a parallel seq scan: -49% to -65% on A/B (target >= 50% met except A 10% vs the 155 ms emulated figure: -49%; vs the 280 ms serial EXPLAIN: -72%).
- With any ordered index on `completed_at` the planner picks `Limit -> Index Scan` at **every** cutoff and also in the generic plan: 0.14-0.41 ms (< 5 ms target). This is what removes the plan flips of H40 (A 10% 154 ms, B 90% 335 ms).
- FAILED: `findFailedRecordsBefore` / `deleteFailedRecords_selectIds` ~0.04-0.15 ms with or without LIMIT (the FAILED set is tiny; with `idx_status` dropped the planner uses `status_retry`, 0.04).
- Both cleanup selects choose the same plans, so the id select inherits the improvement.

## Equivalence check
`eq.sh` (`results/G4G5-eq-{A,B,D,edge}.txt`): cursor-emulated `withMaxRows` original (`H41/eq/orig_*_maxrows.sql`) vs the LIMIT queries, cutpct 0.1 and 0.9, limit 1 / 1000 / 100000 (limit larger than the result), plus FAILED variants limit 1 / 1000:
**A, B, D, edge: 0 FAIL** (29 / 29 / 29 / 31 PASS comparisons incl. H32 and H42). Seeds have unique `completed_at`/`next_retry_at`, so order is exact; ties at the limit boundary are plan-dependent in both forms (no `id` tie-breaker).

## Regression
Query-only change; the index candidates' regression is in H40 (key-query lure of `(status,completed_at)` in A) and write cost. `LIMIT $n` portability: `LIMIT ?` (Postgres, MySQL, MariaDB), `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` (SQL Server, Oracle 12c+, also Postgres).

## Experimenter note
H41 is the prerequisite for any cleanup index: it makes the result independent of cutoff selectivity, scenario and generic/custom planning. As a stand-alone change it already saves ~50% (parallel top-N).

## Decision + reasoning


## Orchestrator decision
**accepted**. per-dialect limit syntax (ledger: `../STATE.md`; final report: `../99-final-report.md`)
