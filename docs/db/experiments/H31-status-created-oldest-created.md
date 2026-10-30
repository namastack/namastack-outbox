# H31: `(status, created_at)` turns findOldestCreatedAtByPartitions into a MIN->Limit index probe
Status: measured
Group: G4 (metrics). Iteration 1.
Target queries: `findOldestCreatedAtByPartitions` (64 / 256 partitions). Side effects to measure:
`findPendingRecords`, `findCompletedRecords()`, `findFailedRecords()` (G6, ordered by `created_at, id`),
`countByStatus` (H30), and every hot-path query that uses `idx_outbox_record_status` today.

## Change

SQL unchanged:

```sql
SELECT MIN(created_at) FROM outbox_record
WHERE partition_no IN (:partitions) AND status = :status;
```

Variant a (replace, preferred): `H31/indexes.sql`

```sql
DROP INDEX IF EXISTS idx_outbox_record_status;
CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at);
```

Variant a' (add only): `H31/indexes-add.sql` (keep `idx_status`, add the same index).

Variant b (modify the partition index instead): `H31/indexes-partition-created.sql`

```sql
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
CREATE INDEX idx_outbox_record_partition_status_created ON outbox_record (partition_no, status, created_at);
```

## Rationale / expected plan

Baseline: `Aggregate -> Index Scan idx_status (status = 'NEW'), Filter partition_no = ANY(...)`: reads every NEW
row of the table and its heap tuple. A 64p 1.0 ms, **B 49.7 ms (16.8k buffers, 151k rows removed)**, C 19.9 ms
(64p bitmap on partition_status_retry) / 25.6 ms (256p parallel seq scan), D 4.6 ms. Cost scales with all NEW rows
of the table, not with the owned ones. Runs every 15 s per instance (`OutboxMetricsCollector.collectPartitionMetrics`).

Variant a: Postgres' MIN/MAX optimisation (`planagg.c`) rewrites `MIN(created_at)` into
`SELECT created_at ... WHERE status = 'NEW' AND partition_no = ANY(...) AND created_at IS NOT NULL ORDER BY created_at LIMIT 1`.
With `status` fixed by equality, `(status, created_at)` delivers `created_at` order, so the expected plan is

```
Result
  InitPlan 1
    -> Limit
         -> Index Scan using idx_outbox_record_status_created  Index Cond: (status = 'NEW' AND created_at IS NOT NULL)
              Filter: (partition_no = ANY (...))
```

It stops at the first NEW row (in creation order) that lies in an owned partition. With owned partitions holding a
fraction f of the NEW rows that are spread over time, that is ~1/f rows (4 for 64 of 256 partitions): expected
**< 0.1 ms in A, B, C and D** (B: 49.7 -> <0.1 ms). It works with the generic plan too (equality on a parameter
still fixes the leading column). Worst case: the owned partitions hold no NEW rows, or only the newest ones; then
the scan walks all NEW rows with a heap fetch each, i.e. the baseline cost (B ~50 ms), never more.
`H31/queries/findOldestCreatedAtByPartitions_noowned.sql` measures that bound.

Variant b: `(partition_no, status, created_at)` gives an index-only aggregate over the NEW rows of the owned
partitions only (ScalarArrayOp on the leading column, 64/256 descents), like `countRecordsByPartitions` today
(B 4.2 ms / 16.8 ms). Expected: B 64p ~4 ms, 256p ~15 ms; scales with the owned backlog. No Limit is possible
because an IN list on the leading column does not return rows in `created_at` order.
Variant b keeps `countRecordsByPartitions` index-only (the third column does not matter for a count), but
changes the index that G1 may want for the key query (`next_retry_at <= now`): coordinate with G1.

Side benefits of variant a (G6, admin, to confirm): `findPendingRecords` / `findCompletedRecords()` /
`findFailedRecords()` (`WHERE status = ? ORDER BY created_at, id`) can use `Incremental Sort (created_at, id)` over
an ordered index scan instead of a full sort that spills (B `findPendingRecords` 212 ms; `findCompletedRecords()`
710-890 ms with a ~650 MB external sort).

Risks of variant a: `idx_status` serves `countByStatus` (H30: expected +10-40% for COMPLETED, larger index) and
is used by the key query and `findIncompleteRecordPages` as an access path for `status = 'NEW'`. The NEW range
of `(status, created_at)` is not deduplicated (B: ~200k entries ~1.1k pages instead of ~150), so those scans
read a few more index pages; plan flips (e.g. BitmapAnd choices in `findIncompleteRecordPages`) must be
checked by the regression run. Write cost: the number of indexes is unchanged; entries are wider (no
deduplication) -> expect a few % more WAL per insert/update, new entries land at the right edge of the
NEW / COMPLETED ranges (append-friendly).

Relation to G1: if G1 adds a partial index on incomplete rows, e.g. `(partition_no, created_at, id) WHERE
completed_at IS NULL`, the planner cannot use it for this query as written, because `status = 'NEW'` does not
imply `completed_at IS NULL` for the planner. That path is H32. H31 variant a is independent of G1's indexes
and gives the better bound (Limit probe vs per-owned-row aggregate).

## Semantic equivalence argument

Index-only change; the SQL text is identical, so results are identical by definition. Still run
`compare-results.sh` (original vs `H31/queries/findOldestCreatedAtByPartitions.sql`, parts 64/256) per scenario
and on `seed-edge` (empty partitions 128..255, FAILED heads, NEW rows not due: all count, because the query has
no `next_retry_at` predicate) as a sanity check of the harness.

## Portability

- MySQL 8 / MariaDB: the "Select tables optimized away" MIN shortcut needs all predicates on the index prefix;
  with the extra `partition_no IN` filter (not in the index) the optimizer does a ref/range scan on
  `(status, created_at)` and may or may not stop at the first match. Phase 3 checks it; the worst case is
  today's cost (all NEW rows).
- SQL Server: `MIN` over an ordered index seek with residual predicate becomes `TOP 1` (Stream Aggregate over
  ordered seek), expected to stop early. Oracle: `INDEX RANGE SCAN (MIN/MAX)` needs no residual filter; with
  the partition filter it may scan the range. Phase 3.
- Variant b is a plain composite index everywhere; the aggregate is index-only in InnoDB, SQL Server, Oracle.
- JPA: `@Table(indexes)` entry `Index(name = "idx_outbox_record_status_created", columnList = "status, createdAt")`
  replacing `idx_outbox_record_status`; same HQL query.
- Mongo: `findOldestCreatedAtByPartitions` is an aggregation `$match {partitionNo $in, status}` + `$group $min`;
  Mongo does not turn `$group/$min` into a limit. A compound `{status: 1, createdAt: 1}` index plus a rewrite
  to `find({status, partitionNo: {$in}}).sort({createdAt: 1}).limit(1)` would be the equivalent. Phase 3.

## Experiment plan

1. Scenarios A, B, C, D; index sets base (from 01-baseline), a, a', b. Use `--ddl` (indexes grow with the seed).
2. `run-explain.sh -q experiments/H31/queries` custom, `--generic`, `--costs` for `findOldestCreatedAtByPartitions`
   and `_noowned` (64 / 256), plus `countByStatus`, `findPendingRecords`, `findCompletedRecords`, `findFailedRecords`.
3. Equivalence: `compare-results.sh` original vs H31 file, parts 64 and 256, A-D and edge.
4. Regression (variants a and b only): `run-suite.sh H31a-<S> <S> --ddl experiments/H31/indexes.sql` and the same
   with `indexes-partition-created.sql`; compare the full summary with base, write cost, sizes.
5. Accept if B 64p drops >= 90% and no hot-path query regresses > 10%; otherwise report which plan flipped.

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

### findOldestCreatedAtByPartitions (SQL unchanged), custom plans, ms (buffers)
| scen | parts | baseline | H08a ctl | a: `idx_status`->`(status,created_at)` (h31a, psr kept) | a, psr dropped (h31a-nopsr) | b: psr->`(partition_no,status,created_at)` (h31b) |
|---|---|---|---|---|---|---|
| A | 64 | 1.00 (510) | 1.03 (510) | **0.04 (4)** | 0.04 (4) | 0.27 (205) |
| A | 256 | 1.23 (510) | 1.13 (510) | **0.04 (4)** | 0.04 (4) | 1.16 (511) |
| B | 64 | 49.65 (16,832) | 45.8 (16,832) | **0.03 (4)** | 0.03 (4) | 4.31 (573) |
| B | 256 | 50.13 (16,832) | 51.0 (16,832) | **0.04 (4)** | 0.04 (4) | 17.99 (2,306) |
| D | 64 | 4.55 (1,858) | 4.61 (1,858) | **0.04 (4)** | 0.04 (4) | 0.52 (216) |
| D | 256 | 4.78 (1,858) | 4.85 (1,858) | **0.04 (4)** | 0.04 (4) | 2.33 (904) |

Generic plans: a 0.04-0.12 ms everywhere; H08a ctl B 80 / 212 ms (64/256p); b B 4.4 / 17.4 ms. Worst case (`_noowned`, owned partitions hold no NEW rows, B): a 47 ms (no worse than the 45 ms baseline), b 0.03 ms (empty range).
Plan for a: `Result -> InitPlan -> Limit -> Index Scan idx_outbox_record_status_created (status = 'NEW', created_at IS NOT NULL), Filter partition_no = ANY`, as predicted.

**Compare with H32** (same SQL plus `AND completed_at IS NULL` on the H08a partial index, no DDL): A 0.04 / 0.06, B 0.04 / 0.06, D 0.03 / 0.05 ms (64 / 256p), generic 0.06-0.10 ms; worst case `_noowned` + predicate B 22.6 ms
(bounded by the incomplete rows instead of all NEW rows). H32 reaches the same result as variant a without touching `idx_status`.

### Cost of H31a / H31b
- `countByStatus(COMPLETED)`: see H30 (replace: +60-200% when all-visible, 5.4x with a dirty VM). Adding `(status,created_at)` next to `idx_status` was not measured separately (an extra index, WAL like h40add, ~+3.5%) because H32 makes it unnecessary.
- Hot path (`results/G4G5-{A,B}-hot`): unchanged in h31a/h31b. Key query filter-on A 64p 0.21 / 0.18 ms, B 0.21 / 0.19; `findIncompleteRecordPages` (Lon) 100 / 1000 keys: A 0.55 / 5.9 (h31a), 0.52 / 4.7 (h31b) vs 0.48 / 4.7 ctl; B 0.81 / 8.1, 0.73 / 7.9 vs 0.72 / 7.9.
  Unrelated flip: `findRecordKeysInPartitions_nofilter` B 64p alternates 0.14 <-> 204 ms between states (the misestimate H05-X fixes): 204 whenever psr exists, 0.14 when psr is dropped.
- Write cost (A, 50k ops, vs H08a 2,476 / 3,189 B WAL per op): h31a ins 2,491 (+0.6%) / upd 3,212 (+0.7%); h31a-nopsr 2,349 (-5.1%) / 3,059 (-4.1%); h31b 2,485 (+0.4%) / 3,197 (+0.3%).
- Sizes: `(status,created_at)` 39 MB (A) / 37 MB (B) vs `idx_status` 6 MB; h31b `(partition_no,status,created_at)` 39 / 37 MB vs psr 68 / 63 MB.
- b keeps `countRecordsByPartitions` index-only (A 0.27, B 4.3 / 17.8 ms) but `findOldestCreatedAtByPartitions` stays proportional to the owned backlog (B 4.3 / 18 ms), a and H32 do not.

## Equivalence check
SQL unchanged. Counts and min(created_at) identical between H08a and after replacing `idx_status` (`index-independence` lines in `results/G4G5-eq-{A,B,D,edge}.txt`). PASS.

## Regression
See above (hot path, WAL, sizes) and H30 for `countByStatus`. No other plan changes except the psr-related `nofilter` flip noted above.

## Experimenter note
Variant a delivers the predicted B 50 -> 0.03 ms but at the price of `countByStatus(COMPLETED)` (H30). The same gain is available with zero DDL through H32's predicate on the H08a partial index. Variant b is dominated by both.
Variant a stays interesting only for dialects without partial indexes (MySQL/MariaDB/Oracle): there `(status, created_at)` ADDED next to the status index is the Phase 3 candidate.

## Decision + reasoning


## Orchestrator decision
Rejected: keep `idx_outbox_record_status` unchanged (countByStatus(COMPLETED) penalty; oldest-created solved by H32). (ledger: `../STATE.md`; final report: `../99-final-report.md`)
