# countRecordsByPartitions(partitions, status)

Group G4 (periodic, metrics). SQL `countByPartitionsStatusQuery`; harness `harness/queries/countRecordsByPartitions.sql`.

```sql
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
```

## Callers and frequency
- `OutboxMetricsCollector.collectPartitionMetrics()` (observability): `countRecordsByPartitions(owned, NEW)`
  every refresh (default 15 s); skipped when no partitions are owned.
- SPI default implementation sums `countRecordsByPartition` (for custom repositories); JDBC, JPA and Mongo override it.

## Baseline (ms (buffers))
| parts | A | B | C | D | E |
|---|---|---|---|---|---|
| 64 | 0.32 (232) | 4.17 (648) | 4.02 (272) | 0.46 (229) | 0.27 (232) |
| 256 | 1.15 (510) | 16.81 (2,676) | 16.28 (1,109) | 2.23 (933) | 1.18 (509) |

Plan: index-only scan of `idx_outbox_record_partition_status_retry` (A/E 256: index scan of `idx_status` with
partition filter). This is the **only** query that uses `partition_status_retry` in the workload mix. Scales with
the NEW rows of the owned partitions: fine. Generic plans equal custom.

## Hypotheses
- H32: if G1 drops/replaces `partition_status_retry` with a partial index on incomplete rows, add the redundant
  `AND completed_at IS NULL` (otherwise the partial index is unusable) and keep `status` in the index
  (INCLUDE) so the count stays index-only.
- H31 variant b (`(partition_no, status, created_at)`) keeps this query index-only.

## Outcome
_pending_
