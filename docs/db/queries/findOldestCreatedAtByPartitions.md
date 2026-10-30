# findOldestCreatedAtByPartitions(partitions, status)

Group G4 (periodic, metrics). SQL `oldestCreatedAtByPartitionsStatusQuery`; harness `harness/queries/findOldestCreatedAtByPartitions.sql`.

```sql
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
```

## Callers and frequency
- `OutboxMetricsCollector.collectPartitionMetrics()` (observability, metric `oldestRecordAge`):
  `(owned partitions, NEW)` every refresh (default 15 s); skipped for an empty set. Only caller.
- JPA: same HQL. Mongo: aggregation `$match {partitionNo $in, status}` + `$group $min createdAt`.

## Baseline (ms (buffers))
| parts | A | B | C | D | E |
|---|---|---|---|---|---|
| 64 custom | 1.00 (510) | **49.65 (16,832)** | 19.91 (16,460) | 4.55 (1,858) | 1.13 (509) |
| 256 custom | 1.23 (510) | **50.13 (16,832)** | 25.63 (16,684) | 4.78 (1,858) | 1.14 (509) |
| 64 generic | 7.41 | 78.87 | 115.29 | 12.53 | 7.55 |
| 256 generic | 11.08 | 212.03 | 310.18 | 27.26 | 11.27 |

Plan: `Aggregate -> Index Scan idx_status (status='NEW'), Filter partition_no = ANY` (C: bitmap on
`partition_status_retry` / parallel seq scan). Reads every NEW row of the table with its heap tuple (B: 151k of
200k removed by the filter at 64 partitions): scales with the table's backlog, not with the owned one.

## Hypotheses
- **H31** `(status, created_at)` (replacing `idx_status`): MIN -> `Limit` ordered index probe, expected < 0.1 ms in
  all scenarios (worst case: owned partitions without NEW rows = today's cost). Variant b
  `(partition_no, status, created_at)`: index-only aggregate over owned NEW rows (B ~4 ms).
- H32: with a G1 partial index on incomplete rows, needs the redundant `completed_at IS NULL` to use it;
  LATERAL per-partition probe as a Postgres-only option.

## Outcome
_pending_
