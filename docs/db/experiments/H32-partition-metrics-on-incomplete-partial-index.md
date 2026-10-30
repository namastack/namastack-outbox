# H32: partition metrics on a partial "incomplete rows" index need a redundant `completed_at IS NULL`
Status: measured
Group: G4 (metrics). Iteration 1.
Target queries: `countRecordsByPartitions`, `countRecordsByPartition`, `findOldestCreatedAtByPartitions`.

## Change

DDL under test (the G1 shape is an ASSUMPTION; replace it with G1's accepted DDL before running):

- variant i `H32/indexes-incomplete.sql`:
  ```sql
  CREATE INDEX idx_outbox_record_incomplete_partition_created
      ON outbox_record (partition_no, created_at, id) WHERE completed_at IS NULL;
  DROP INDEX idx_outbox_record_partition_status_retry;
  ```
- variant ii `H32/indexes-incomplete-status.sql`: same index with `INCLUDE (status)`.

Query rewrite (all three queries get one redundant predicate):

```sql
-- countRecordsByPartitions
SELECT COUNT(*) FROM outbox_record
WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL;
-- countRecordsByPartition
SELECT COUNT(*) FROM outbox_record
WHERE partition_no = :partitionNo AND status = :status AND completed_at IS NULL;
-- findOldestCreatedAtByPartitions
SELECT MIN(created_at) FROM outbox_record
WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL;
```

Informational Postgres-only variant (`H32/queries/findOldestCreatedAtByPartitions_lateral.sql`): one ordered
Limit-1 probe per owned partition via `unnest(ARRAY[...]) CROSS JOIN LATERAL (... ORDER BY created_at LIMIT 1)`.

## Rationale / expected plan

- Today `countRecordsByPartitions` is the only user of `partition_status_retry` (index-only, B 4.2 / 16.8 ms for
  64 / 256 partitions; A 0.3 / 1.2 ms). If G1 drops or replaces that index, the count falls back to
  `idx_status` + heap filter (A/256 already does: `Index Scan idx_status, Filter partition_no`), i.e. it scans all
  NEW rows of the table with heap fetches (B ~50 ms, like `findOldestCreatedAtByPartitions` today).
- A partial index `WHERE completed_at IS NULL` is only considered when the query's WHERE clause implies the index
  predicate. `status = 'NEW'` does not imply `completed_at IS NULL` for the planner, so **none of the G4 queries can
  use a G1 partial index as written**. Adding `AND completed_at IS NULL` makes the predicate provable.
- Expected plans with the rewrite:
  - count, variant ii: `Aggregate -> Index Only Scan idx_..._incomplete_partition_created, Index Cond partition_no = ANY,
    Filter status = 'NEW'` (status from INCLUDE): ~ the baseline (reads NEW + FAILED entries of owned partitions;
    FAILED rows are few). Variant i: `Index Scan` + heap fetch per incomplete row for `status`: B 64p ~15-30 ms.
  - oldest, variant i/ii: aggregate over the incomplete rows of the owned partitions (no Limit possible with an
    IN list): B 64p ~5 ms (ii, index-only) to ~20 ms (i). H31 variant a (< 0.1 ms) is better for this query.
  - oldest, LATERAL: 64/256 Limit-1 probes, each stopping at the first NEW row of the partition (skips FAILED
    heads): < 0.5 ms in every scenario; Postgres only.
- Recommendation for Phase 2: if `partition_status_retry` is dropped, the replacement partial index should carry
  `status` (key column or INCLUDE) so that `countRecordsByPartitions` stays index-only, and the three queries get
  the redundant predicate. If `partition_status_retry` stays, H32 is not needed.

## Semantic equivalence argument

The added predicate is redundant iff every `NEW` row has `completed_at IS NULL`:
- `OutboxRecord.Builder.build` creates records with `status = NEW, completedAt = null`.
- `completedAt` is only set in `markCompleted` together with `status = COMPLETED`; `markFailed` keeps it null;
  the only reset (`OutboxRecordProcessor.completeRecord` on failure) restores both fields together.
- The library never moves a COMPLETED record back to NEW. The key query and `findOldestDueNextRetryAtByPartitions`
  already rely on the same invariant (`completed_at IS NULL` = NEW or FAILED, `00-orchestration-prompt.md` 2).
- Exception: rows written by applications through the public `save`/`update` SPI or `OutboxRecord.restore` with
  `status = NEW` and a non-null `completedAt`. Such rows are already treated inconsistently by the anti-join.
  Listed as an open question, not a blocker.
For `status = 'COMPLETED'` callers (none exist; the metrics call these methods with NEW only) the rewrite would be
wrong: the methods take `status` as a parameter, so the code must add the predicate only when `status != COMPLETED`
or the SPI must document that the partition metrics count incomplete statuses only. Both callers pass NEW.

Check: `compare-results.sh` original (harness/queries) vs H32 files on A-E and `seed-edge` (FAILED heads, not-due
NEW, empty partitions 128..255 with parts=256), variables parts=64/256, status=NEW and status=FAILED.

## Portability

- Partial indexes: Postgres yes; SQL Server filtered index (`WHERE completed_at IS NULL`) yes (the predicate is
  a literal `IS NULL`, so parameterisation does not prevent matching; verify in Phase 3);
  MySQL / MariaDB / Oracle: no partial indexes. There the redundant predicate is harmless (it is a filter) and the
  plain composite index decided by G1/Phase 2 is used. Oracle: a function-based index can emulate it.
- LATERAL: Postgres, MySQL 8.0.14+, Oracle 12c+; SQL Server needs `CROSS APPLY`; MariaDB has no LATERAL.
  Hence informational only.
- JPA: the HQL queries get the same `and o.completedAt is null`; the partial index cannot be declared in
  `@Table(indexes)` (needs DDL). Mongo: whether a `partialFilterExpression` on `completedAt: null`
  is accepted and used depends on the server version (null equality in partial filters is restricted); Phase 3
  checks it, otherwise Mongo keeps a full compound index.

## Experiment plan

1. Wait for G1's proposed partial index; put its DDL into `indexes-incomplete*.sql` (keep the INCLUDE variant).
2. Scenarios A, B, C, D; `run-explain.sh -q experiments/H32/queries` (custom, `--generic`, `--costs`) with variant
   i and ii, and the original `harness/queries/{countRecordsByPartition,countRecordsByPartitions,findOldestCreatedAtByPartitions}`
   on the same containers (to show that without the predicate the partial index is ignored).
3. Equivalence as above.
4. No separate regression run: the DDL belongs to G1; this hypothesis only adds predicates.

## Open questions

- Should the SPI state that `countRecordsByPartition(s)` / `findOldestCreatedAtByPartitions` are for incomplete
  statuses only (all callers pass NEW)? That would make the redundant predicate a documented part of the contract.
- Deprecated metrics module: `OutboxPartitionMetricsMeterBinder` registers 4 gauges (`assigned.count`,
  `pending.records.total/max/avg`) that each call `getProcessingStats()`, which runs `countRecordsByPartition` once
  per owned partition. One scrape = 4 x owned partitions queries (1,024 for a single instance with 256 partitions).
  Each is cheap (0.03-0.1 ms server time) but it is ~1k round trips per scrape. Behavioural fix (cache the stats
  per scrape or use `countRecordsByPartitions`) is out of scope for index work.

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

### Which statuses do callers pass?
`countRecordsByPartitions`, `countRecordsByPartition`, `findOldestCreatedAtByPartitions` are called with `NEW` only (observability `OutboxMetricsCollector.collectPartitionMetrics`, deprecated `OutboxPartitionMetricsProvider`).
`AND completed_at IS NULL` is valid for NEW and FAILED (equivalence below, status=FAILED included) and WRONG for COMPLETED (returns 0): the SPI must document "incomplete statuses only" or add the predicate only for `status != COMPLETED`.

### Results, ms (buffers), custom unless noted. "orig" = unchanged SQL, "+INC" = with `AND completed_at IS NULL`
H08a as-is (partition_status_retry `psr` present, so orig keeps using psr):

| query | scen / parts | baseline | H08a ctl (orig) | H08a +INC (uses `incomplete_created`) | +INC generic |
|---|---|---|---|---|---|
| countRecordsByPartitions | A 64 / 256 | 0.32 / 1.15 | 0.26 (psr) / 1.11 | 0.78 / 0.85 | 5.7 / 17.5 |
| | B 64 / 256 | 4.17 / 16.81 | 4.14 / 16.70 (psr) | 17.4 / 18.3 | **71 / 202** |
| | D 64 / 256 | 0.46 / 2.23 | 0.52 / 2.25 | 2.4 / 2.7 | 11.5 / 25 |
| countRecordsByPartition (1 partition) | A / B / D | 0.03 / 0.10 / 0.04 | same | 0.06 / 0.47 (psr kept, planner prefers it) / 0.04 | - |
| findOldestCreatedAtByPartitions | A 64 / 256 | 1.00 / 1.23 | 1.03 / 1.13 | **0.04 / 0.06** | 0.06 / 0.09 |
| | B 64 / 256 | 49.65 / 50.13 | 45.8 / 51.0 | **0.04 / 0.06** | 0.06 / 0.09 |
| | D 64 / 256 | 4.55 / 4.78 | 4.61 / 4.85 | **0.03 / 0.05** | 0.05 / 0.08 |
| oldest, owned partitions empty (`_noowned`) | B 64 / 256 | 45 / 44 | 45 | 22.6 / 22.8 (`results/G4G5x-B`) | - |

Index changes on top of H08a (B unless noted):

| state | count 64p (orig / +INC) | count 256p (orig / +INC) | countRecordsByPartition (orig / +INC) | oldest 64p (orig / +INC) | notes |
|---|---|---|---|---|---|
| H08a (psr kept) | 4.1 / 17.4 | 16.7 / 18.3 | 0.10 / 0.47 | 45.8 / 0.04 | |
| **drop psr** (h32psr) | 48.2 / 17.3 | 49.9 / 18.1 | 40.0 / 14.4 | 48.3 / 0.05 | orig falls back to `idx_status` + heap filter; +INC generic 79 / 213 ms |
| drop psr + **ii** `(partition_no,created_at,id) INCLUDE(status) WHERE completed_at IS NULL` (h32ii) | 47.6 / **5.2** | 48.8 / 21.7 | 42.8 / **0.10** | 47.1 / 0.04 | +INC generic 5.7 / 22.2 (generic-safe); ii 15 MB vs psr 63 MB |
| ii + drop `idx_status` (h32ii-nostatus) | 78 / 5.3 | 84 / 22.1 | 75 / 0.10 | 88 / 0.05 | `idx_status` still needed by orig SQL and `countByStatus` |
| A: ii | 1.03 / 0.21 | 1.15 / 0.75 | 1.23 / 0.03 | 1.01 / 0.05 | |
| D: ii | 4.63 / 0.44 | 5.59 / 2.25 | 4.09 / 0.03 | 4.52 / 0.03 | |

Answers:
1. **Can the H08a partial index serve the metrics queries?** `findOldestCreatedAtByPartitions` +INC: yes, as an ordered scan of `incomplete_created` with `Limit 1` (partition/status evaluated on the INCLUDEd columns): B 45.8 -> 0.04 ms, A 1.0 -> 0.04, D 4.6 -> 0.03, generic-safe, worst case 22.6 ms (vs 45). Zero DDL.
   `countRecordsByPartitions` +INC: usable, but only as a full index-only scan of the partial index with a filter (`partition_no` is not a leading column): reads all incomplete rows, B 17.4 / 18.3 ms custom (4x worse than psr at 64p, equal at 256p),
   and the generic plan is 4-12x worse (B 71 / 202 ms: the `partition_no = ANY($1..$n)` filter is not hashed in a generic plan). `countRecordsByPartition` (deprecated module, 4 x owned partitions per scrape): 14.4 ms (B) instead of 0.10 ms.
2. **Can `idx_outbox_record_partition_status_retry` be dropped or modified?** Not for free. Dropped alone: orig SQL B 48 / 50 / 40 ms, +INC B 17 / 18 / 14 ms (generic up to 202 ms); WAL -5.5% ins / -5.3% upd.
   Side effect: with psr dropped, `findRecordKeysInPartitions_nofilter` B 64p is 0.14 ms instead of 204 ms (psr lures the planner; accepted fix for that is H05-X). Replacing psr with `ii`: count 5.2 / 21.7 ms, single-partition 0.10 ms, generic-safe, WAL ins +1.7% / upd -5.2% vs H08a,
   but **`ii` lures the hot path**: A `findRecordKeysInPartitions_filter` 64p 0.20 -> 4.2 ms (BitmapAnd with `ii`, 6.4k buffers) and B nofilter 204 ms (as with psr). `(partition_no,status,created_at)` (h31b) keeps count index-only (4.3 / 17.8) but is the same lure class as psr.
3. **Can `idx_outbox_record_status` be dropped or modified?** After the +INC oldest rewrite it serves `countByStatus`, cleanup and admin queries only. Dropping: COMPLETED count +60-80% (A 23.8 -> 42.4, B 19.3 -> 37.3), WAL -2%. Modifying it: H30 (2-5x worse). Keep unchanged.

## Equivalence check
`results/G4G5-eq-{A,B,D,edge}.txt`: original vs +INC for `countRecordsByPartitions`, `countRecordsByPartition`, `findOldestCreatedAtByPartitions`, parts 64 and 256, status NEW and FAILED, plus the informational LATERAL variant:
**A, B, D, edge: all PASS (0 FAIL)** (29-31 comparisons per scenario including H41/H42). Invariant: 0 rows with `status='NEW' AND completed_at IS NOT NULL` in every scenario. With status=COMPLETED the +INC form returns 0 (not equivalent).

## Regression
- Hot path (`results/G4G5-{A,B}-hot`): only the `ii` states regress the key query (A 64p filter-on 0.20 -> 4.2 ms; `status_completed` states 4.6-5.1 ms, see H40). `findIncompleteRecordPages` (Lon) unchanged in every state (A 0.12 / 0.48 / 4.7, B 0.14 / 0.72 / 7.9 ms for 10 / 100 / 1000 keys).
- Write cost (A, 50k ins + 50k upd, WAL B/op; H08a 2,476 / 3,189; baseline 2.4 / 3.3 kB): drop psr 2,341 / 3,019 (-5.5% / -5.3%); psr -> ii 2,519 / 3,023 (+1.7% / -5.2%); drop `idx_status` 2,426 / 3,118 (-2.0% / -2.2%); drop both psr and `idx_status` 2,263 / 2,944 (-8.6% / -7.7%). ms per 1k ops 24.5-27.4 ins, 32.0-35.4 upd (tps noise ~5%).
- Sizes (A / B): psr 68 / 63 MB; ii ~0 / 15 MB; `idx_status` 6 MB; `incomplete_created` 1 / 20 MB.

## Experimenter note
The cheap, safe part of H32 is the `findOldestCreatedAtByPartitions` rewrite: three orders of magnitude, no DDL, no write cost. The count queries do not profit from the H08a partial index (no leading `partition_no`); psr stays the better count index,
and a partial `ii` that would replace it lures the key query.

## Decision + reasoning


## Orchestrator decision
**accepted for findOldestCreatedAtByPartitions only**. counts rejected (B 4.1/16.7 → 17/18, generic 71/202); callers pass NEW only; keep partition_status_retry (ledger: `../STATE.md`; final report: `../99-final-report.md`)
