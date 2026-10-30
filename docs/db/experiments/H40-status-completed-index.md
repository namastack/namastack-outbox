# H40: `(status, completed_at)` index for COMPLETED retention (add, or replace `idx_status`)
Status: measured
Group: G5 (cleanup). Iteration 1.
Target queries: `findCompletedRecords(completedBefore, limit)` (`findByStatusAndCompletedBeforeQuery`),
`deleteCompletedRecords` step 1 (`findIdsByStatusAndCompletedBeforeQuery`). Guard: `countByStatus` (H30) and every
query using `idx_outbox_record_status` (replace variant).

## Change

SQL unchanged (still `.withMaxRows(limit)`):

```sql
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at;   -- find
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at;  -- delete step 1
```

- variant add `H40/indexes-add.sql`:
  `CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);`
  This is exactly the optional index `namastack-outbox-docs/docs/reference/retention.md` ("Indexes") and ADR 0003
  recommend today; the hypothesis tests making it part of the default schema.
- variant replace `H40/indexes-replace.sql`: drop `idx_outbox_record_status`, add the same index. Every
  `status = ?` query keeps a status-leading index; NEW and FAILED rows have `completed_at` NULL, so their entries are
  equal keys `(NEW, NULL)` / `(FAILED, NULL)` and stay deduplicated as in the single-column index.

## Rationale / expected plan

Baseline (10th percentile cutoff, ~99k qualifying rows, limit 1000): no usable index, so
`Gather Merge -> Sort (external merge, 5.6-65 MB) -> Parallel Seq Scan` (all 90.9k heap pages, 900k rows removed).
As executed by pgjdbc (`withMaxRows` => no workers, the Sort still consumes everything):
find **A 273 / B 211 / D 253 / E 270 ms**, id select **A 170 / B 159 / D 157 / E 171 ms**, independent of `limit`.
C (no COMPLETED rows) 0.03 ms.

With the index, the planner (no LIMIT visible) compares `Index Scan idx_..._status_completed (status = 'COMPLETED'
AND completed_at < cutoff)` returning rows already in `completed_at` order (no Sort) against seq scan + sort.
Heap order follows `created_at` and `completed_at = created_at + 1 s` in the seeds (correlation ~1), so the index
scan is cheap and should win at 10%. Because the index scan streams, the pgjdbc portal suspends after `limit`
rows: real cost ~ `limit` index entries + their heap pages (~1000 / 11 rows per page ~ 90 pages): **expected
~0.5-2 ms instead of 157-273 ms**. EXPLAIN ANALYZE of the unlimited query will still show the full 99k rows
(~20-40 ms); the as-executed cost is measured with the cursor emulation `H40/manual/maxrows-completed.sql`
(cursor_tuple_fraction = 1 => same plan as the unnamed portal, no parallel workers, MOVE stops after `limit` rows).

Selectivity risk (why H41 exists): for a large cutoff (first cleanup of a table with long history: 90-100% of
COMPLETED rows qualify) the planner, still unaware of the limit, may pick seq scan + full sort again (B/D: sort of
~900k rows, ~650 MB spill like `findCompletedRecords()` 710-890 ms), and the "call repeatedly until < limit" loop
pays that per batch. The query files sweep `cutpct` = 0.1 / 0.5 / 0.9 / 1.0 to find the switch point.
In production the correlation is lower than in the seeds (an update NEW -> COMPLETED writes a new tuple version
wherever free space exists), which moves the switch point to smaller cutoffs.

FAILED retention is not affected: `(status, next_retry_at)` already serves it (`status_retry`), and FAILED rows
are few. If G1/Phase 2 drops `status_retry`, FAILED retention degrades to `status` index + sort of all FAILED rows
(fine for hundreds, linear for a failure storm).

Expected side effects:
- size: ~40-45 MB (like `status_retry` 45 MB; COMPLETED entries are unique, NEW/FAILED deduplicated).
- write cost, add variant: one more index on every insert and on every `updateProcessingState` (never HOT):
  expected +8-15% WAL per update (baseline 3.3 kB/op with 6 indexes), +5-10% per insert. Replace variant: index
  count unchanged; entries wider than in `idx_status` -> a few % more WAL. New COMPLETED entries are appended at
  the right edge of the COMPLETED range (monotonic `completed_at`), so no random page splits.
- `countByStatus(COMPLETED)` (replace variant): H30 expects +10-40% (non-deduplicated entries).
- hot path (replace variant): `status = 'NEW'` range scans stay deduplicated and equally small; plan flips must
  still be ruled out by the regression run (key query, `findIncompleteRecordPages` BitmapAnd).

## Semantic equivalence argument

Index-only change; SQL and `withMaxRows` unchanged. The result is the same set and order, with one caveat that
exists today already: `ORDER BY completed_at` has no tie-breaker, so with equal `completed_at` values the rows
inside a tie (and which tied rows fall inside the first `limit`) depend on the plan. Seeds A-E have unique
`completed_at`, so `compare-results.sh` must PASS there. The contract (`OutboxRecordRepository` KDoc: "sorted by
completion time ascending") does not order ties, so a tie difference is not a contract violation (if the
maintainers want determinism, add `, id` to ORDER BY; with this index Postgres then uses an Incremental Sort).

## Portability

- Plain composite index: valid DDL in all dialects (`CREATE INDEX ... (status, completed_at)`), already documented
  in retention.md for relational databases. MySQL/MariaDB InnoDB secondary indexes carry the primary key, so the
  id select is index-only there. Oracle indexes the row because `status` is NOT NULL. SQL Server fine.
- Real limits: JPA (`setMaxResults`) and Mongo (`.limit()`) already send a real limit, so they get the early stop
  from this index regardless of selectivity; only the JDBC module relies on `withMaxRows` (H41).
- JPA: add `Index(name = "idx_outbox_record_status_completed", columnList = "status, completedAt")`
  (replace variant: remove `idx_outbox_record_status`).
- Mongo: `{status: 1, completedAt: 1}` (`status_completed_idx` in retention.md) as a `@CompoundIndex`.

## Experiment plan

1. Scenarios A, B, D (C/E for the regression only; C has no COMPLETED rows). Index sets: add, replace (`--ddl`).
2. `run-explain.sh -q experiments/H40/queries` custom and `--set max_parallel_workers=0 --tag serial`, all `cutpct`
   variants; `--costs` once to see the planner's estimate for index vs seq scan at each cutoff.
3. Cursor emulation of `withMaxRows`: `H40/manual/maxrows-completed.sql` with cutpct 0.1 / 0.5 / 0.9 / 1.0 and
   limit 100 / 1000 / 10000 on base and add sets (median of the last 3 `Time:` lines). Optional: confirm one point
   with pgjdbc (`harness/jdbc` probe adapted to the cleanup query).
4. Equivalence: `compare-results.sh <c> harness/queries/findCompletedRecordsBefore.sql experiments/H40/queries/findCompletedRecordsBefore.sql`
   (and the id select) on the indexed container vs a base container: compare the md5 lines across containers
   (identical SQL, different index sets), A, B, D and edge.
5. Regression for both variants: `run-suite.sh H40add-<S> <S> --ddl experiments/H40/indexes-add.sql` (and replace)
   for A, B, D: full summary vs base, write cost, sizes.
6. Accept if the as-executed cleanup select is < 5 ms at 10% cutoff in A/B/D and no hot-path regression > 10%;
   choose add vs replace by the H30 delta on `countByStatus` and the write-cost delta.

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

Cleanup selects, scenarios A and B (C has no COMPLETED rows; D skipped for G5). Cutoff = percentile 0.1 / 0.9 of `completed_at`, limit 1000.
Three ways of measuring: (1) `EXPLAIN ANALYZE` of the unchanged SQL with `max_parallel_workers=0` (runs the whole result); (2) **as-executed `withMaxRows`** = cursor emulation `H40/manual/maxrows-completed.sql` (planned without limit, stops after 1000 rows; `results/G4G5-{A,B}-g5/maxrows-emulation.txt`, median of 3);
(3) real `LIMIT` (H41). Index states on top of H08a: add `(status,completed_at)` (h40add), replace `idx_status` (h40repl), H07 non-covering `(completed_at,created_at,id)` (h07), and extra: partial `(completed_at) WHERE status='COMPLETED'` (h40p, `results/G4G5x-B`).

### As executed with withMaxRows(1000) (cursor emulation), ms
| scen | cutoff | baseline / H08a ctl | h40add | h40repl | h07 | h40p (B only) |
|---|---|---|---|---|---|---|
| A | 10% | 155 (Seq+Sort) | **154 (Seq+Sort: planner ignores the index)** | 37 (Bitmap+Sort) | 40 (Bitmap+Sort) | - |
| A | 90% | 385 | 0.32 (Index Scan) | 0.24 | **382 (Seq+Sort)** | - |
| B | 10% | 152 | 0.39 | 0.28 | 33 (Bitmap+Sort) | see below |
| B | 90% | 339 | **335 (Seq+Sort)** | **339 (Seq+Sort)** | 337 | see below |

Without a real LIMIT the index alone is unreliable: the unlimited plan is chosen on total cost, and the winner flips with scenario and cutoff (A/B differ although the index is the same). That is the "large cutoff risk" of the plan, and it appears already at 10% in A.

### EXPLAIN ANALYZE of the unchanged SQL (serial, whole result), ms
| query | scen/cutoff | baseline | H08a ctl | h40add | h40repl | h07 |
|---|---|---|---|---|---|---|
| findCompletedRecordsBefore (select *) | A 10% | 273 | 280 | 12.6 | 15.9 | 18.0 |
| | A 90% | - | 1,415 | 118 | 117 | 164 |
| | B 10% | 211 | 217 | 12.9 | 11.5 | 17.7 |
| | B 90% | - | 1,157 | 92.6 | 91.7 | 140 |
| deleteCompletedRecords_selectIds (id only) | A 10% | 170 | 170 | **169 (no change)** | 49 | 53 |
| | A 90% | - | 511 | 231 | 231 | **509** |
| | B 10% | 159 | 161 | 20.3 | 20.7 | 41.6 |
| | B 90% | - | 429 | **433** | **423** | **430** |
| findFailedRecordsBefore / deleteFailedRecords_selectIds | A/B | 0.11 / 0.08 | 0.11-0.12 / 0.07-0.08 | same | 0.04 (status_retry) | same |

### With the real LIMIT (H41 SQL), ms (buffers)
| query | scen/cutoff | H08a ctl (no index) | h40add | h40repl | h07 | h40p |
|---|---|---|---|---|---|---|
| findCompletedRecordsBefore | A 10% / 90% | 79.8 / 132.5 (90,939) | **0.18 / 0.14 (98)** | 0.14 / 0.14 | 0.19 / 0.20 (103) | - |
| | B 10% / 90% | 76.9 / 119.6 | 0.17 / 0.14 | 0.19 / 0.14 | 0.17 / 0.17 | 0.13 / 0.14 (96) |
| deleteCompletedRecords_selectIds | A 10% / 90% | 79.0 / 120.5 | 0.21 / 0.20 | 0.31 / 0.20 | 0.22 / 0.21 | - |
| | B 10% / 90% | 78.4 / 106.5 | 0.27 / 0.24 | 0.31 / 0.23 | 0.22 / 0.22 | 0.18 / 0.19 |
| findCompletedRecordsBefore **generic** | A 10% / 90% | 106.7 / 142.8 | 0.16 / 0.30 | 0.19 / 0.16 | 0.23 / 0.22 | - |
| | B 10% / 90% | 86.6 / 115.5 | 0.15 / 0.17 | 0.16 / 0.15 | 0.21 / 0.41 | **87.6 / 115.5 (partial index cannot be proven with `status = $1`)** |

### Side effects of the candidates
| | h40add | h40repl | h07 | h40p |
|---|---|---|---|---|
| WAL ins / upd per op (H08a 2,476 / 3,189) | 2,560 / 3,301 (+3.4% / +3.5%) | 2,504 / 3,227 (+1.1% / +1.2%) | 2,604 / 3,361 (+5.2% / +5.4%) | 2,495 / 3,247 (+0.8% / +1.8%) |
| size A / B | 39 / 32 MB | 39 / 32 MB (idx_status gone) | 73 / 73 MB | 17 MB (B) |
| `countByStatus(COMPLETED)` | unchanged (22.6 A, 19.1 B) | 74 ms A/D, 33 B; 445 ms dirty-VM (H30) | unchanged | unchanged (19.8) |
| hot path | **A `findRecordKeysInPartitions_filter` 64p 0.20 -> 5.1 ms** (plan: Sort over Index Scan `status_completed`) | **4.65 ms (same)** | unchanged (0.19) | not run |
| FAILED cleanup | unchanged | `status_retry` used, 0.04 | unchanged | - |

## Equivalence check
SQL and `withMaxRows` unchanged; results are index-independent by construction. Unique `completed_at` in seeds, so the H41 comparison against the cursor reference (see H41) is exact. PASS.

## Regression
`(status,completed_at)` (add or replace) changes the hot-path plan in A: `findRecordKeysInPartitions_filter` 64p 0.20 -> 4.65-5.12 ms (25x; `Limit(Sort(Nested Loop Anti Join(Index Scan status_completed, ...)))`), because `status='NEW' AND completed_at IS NULL` matches the new index (NULL keys). B and 256p unaffected. H07 and h40p do not change hot plans.
`findIncompleteRecordPages` (Lon) unchanged in all states.

## Experimenter note
- H40 alone does not meet the target (< 5 ms as executed at every cutoff): withMaxRows leaves plan choice to total cost and it flips (A 10%: 154 ms with h40add; B 90%: 335 ms with either). H40/H07 + H41 (real LIMIT) is 0.14-0.4 ms at every cutoff, custom and generic, A and B (target met).
- H07 vs H40 for the selects: with the real LIMIT they are equivalent (0.17-0.23 vs 0.14-0.31 ms, 98 vs 103 buffers). H07 non-covering costs the most (+5.3% WAL, 73 MB), but it does not lure the key query and does not touch `countByStatus`. `(status,completed_at)` add: +3.5% WAL but lures the key query in A (25x on a hot path); replace: +1.2% WAL but `countByStatus(COMPLETED)` 2-5x (H30).
  Without the real LIMIT H07 is the worst (A 90%: 382 ms).
- The partial `(completed_at) WHERE status='COMPLETED'` is cheapest (+0.8% / +1.8% WAL, 17 MB, no hot-path effect, count unchanged), but works only while the plan is custom: `status = $1` generic plans do not use it (87-115 ms). A cleanup loop on a pooled connection reaches pgjdbc's generic-plan switch (5 executions), so it is not robust unless the status is inlined as a literal.
- The retention docs already say this index is optional (cleanup has no library caller). The H41 rewrite alone (no index) gives 77-133 ms instead of 152-1,415 ms.

## Decision + reasoning


## Orchestrator decision
rejected as default (optional, doc only). if users add a cleanup index: prefer H07 form (no hot-path effect, +5.2% WAL, 73 MB) (ledger: `../STATE.md`; final report: `../99-final-report.md`)
