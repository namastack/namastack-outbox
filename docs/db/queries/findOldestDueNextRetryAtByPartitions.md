# findOldestDueNextRetryAtByPartitions

Group G1 (shares the anti join with `findRecordKeysInPartitions`). Class: **periodic** (metrics).

## Callers and frequency

- `OutboxMetricsCollector.collectPartitionMetrics` (`namastack-outbox-observability`), once per metrics collection
  (overdue record age gauge) with the instance's owned partitions, `status = NEW`, `now = clock.instant()` and
  `ignoreRecordKeysWithPreviousFailure = stopOnFirstFailure` (default **true** -> filter variant).
- No `withMaxRows`: runs with parallel workers when the planner chooses them. Returns `null` without a query when
  `partitions` is empty. JPA: JPQL `singleResult`; Mongo: aggregation.

## Contract (SPI KDoc)

Earliest `next_retry_at` of records in the partitions that are due at `now` (inclusive), using the same eligibility
predicate as `findRecordKeysInPartitions` (filter on: records blocked by an earlier incomplete record of the same key
are excluded). `null` when nothing matches. "Persistence implementations must provide this method using a single
aggregate query."

## Current SQL (`JdbcOutboxRecordRepository`)

```sql
-- oldestDueNextRetryAtQueryWithPreviousFailureFilter
SELECT MIN(o.next_retry_at)
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )

-- oldestDueNextRetryAtQueryWithoutPreviousFailureFilter
SELECT MIN(o.next_retry_at)
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
```

Harness: `harness/queries/findOldestDueNextRetryAtByPartitions_{filter,nofilter}.sql`.

## Baseline (Postgres 18, `01-baseline.md`, custom plans, ms (buffers))

| Variant | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| filter on | 64 | 4.7 (7,374) | **165 (106,371)** w1, spill | 66 (33,368) w2 | 39 (7,273), 358k rm | 4.7 (7,287) |
| filter on | 256 | 15.7 (27,848) w2 | **214 (106,372)** w1, spill | 84 (33,368) w2 | 139 (7,273), 1.9M rm | 14.7 (26,551) w2 |
| filter off | 64 | 0.04 (4) | 0.04 (4) | 0.03 (4) | 0.03 (4) | 0.04 (4) |
| filter off | 256 | 0.04 (4) | 0.05 (4) | 0.04 (4) | 0.04 (4) | 0.04 (4) |

- Filter on: same plan shape and cost as the key query with the filter (parallel hash anti join over all incomplete
  rows in B/C, quadratic join filter in D). The `MIN` cannot use the min/max index optimisation because of the anti join.
- Filter off: `idx_outbox_record_status_retry` ordered scan + `Limit 1` (min/max optimisation), the only real user of
  that index.
- Raw plans: `results/base-<S>/findOldestDueNextRetryAtByPartitions_{filter,nofilter}-parts{64,256}[-generic|-costs].txt`.

## Hypotheses

| H | Change | Expected (B, filter on, 64p) | Status |
|---|---|---|---|
| [H01](../experiments/H01-sargable-predecessor-bound.md) | `older.created_at <= o.created_at` in the anti join | D 39 -> ~5 ms; B uncertain | proposed |
| [H02](../experiments/H02-partial-incomplete-key-index.md) | H01 + partial probe index | B ~50-80 ms | proposed |
| [H03](../experiments/H03-covering-partition-incomplete-index.md) | H02 + covering partial outer index, outer `completed_at IS NULL` | B ~10-25 ms | proposed |
| [H04](../experiments/H04-head-of-key-early-stop-limit.md) | `SELECT (SELECT next_retry_at ... ORDER BY next_retry_at LIMIT 1)` on `status_retry` + H02 probe | < 1 ms | proposed |

Filter off is not targeted (0.04 ms); regression runs must confirm it keeps the `status_retry` + `Limit` plan.
If H04 is accepted, `status_retry` gains a second user (Phase 2 must not drop it without a replacement).

Note on the KDoc "single aggregate query": H04 is a single query returning one row; if the wording is read strictly
as "aggregate function", the KDoc would need adjusting (no behaviour change).

## Iteration 2 (proposed)

Iteration 1: H04 (scalar `ORDER BY next_retry_at LIMIT 1` on `status_retry` + probe) measured MIN on B 267/364 →
0.17/0.32 ms, D 37/139 → 0.06/0.09 ms, worst case 0.04 ms; MIN off unchanged (0.04 ms). No open issue for this query.

- SQL: unchanged from H04 in H05–H08 (filter on), original (filter off).
- [H06](../experiments/H06-probe-on-existing-key-completed-index.md) / [H08](../experiments/H08-g1-index-set-and-drops.md):
  the anti-join probe moves from the partial `incomplete_key` to the existing `record_key_completed_created`
  (`record_key = ? AND completed_at IS NULL AND created_at <= ?`); expected +1 heap visit per probe (tie check
  needs `id`), still sub-ms.
- `idx_outbox_record_status_retry` is **not** a drop candidate: it is the outer index of both MIN variants (and of
  G5 `findFailedRecords(cutoff)`); its 0–1 baseline scans reflect the old hash-join plan. Possible Phase 2 replacement:
  partial `(status, next_retry_at) WHERE completed_at IS NULL` with `completed_at IS NULL` added to the MIN and
  failed-cleanup SQL (saves the entry on completing updates).

## Outcome
_pending (Phase 1 iteration 1)_
