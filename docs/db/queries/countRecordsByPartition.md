# countRecordsByPartition(partition, status)

Group G4 (periodic). SQL `countByPartitionStatusQuery`; harness `harness/queries/countRecordsByPartition.sql`.

```sql
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
```

## Callers and frequency
- Deprecated `namastack-outbox-metrics` `OutboxPartitionMetricsProvider.getProcessingStats()`: once per owned
  partition with NEW. `OutboxPartitionMetricsMeterBinder` has 4 gauges (`outbox.partitions.assigned.count`,
  `pending.records.total/max/avg`) that each call `getProcessingStats()` on every read: one scrape = 4 x owned
  partitions queries (1,024 for a single instance owning 256 partitions).
- SPI default of `countRecordsByPartitions`.

## Baseline (ms (buffers))
A 0.03 (4), B 0.10 (8), C 0.09 (8), D 0.04 (3), E 0.04 (4). Index-only scan of `partition_status_retry`.
Server time is negligible; the cost is the number of round trips per scrape (behavioural, see H32 open questions).

## Hypotheses
- H32 (same redundant predicate as `countRecordsByPartitions` if `partition_status_retry` is replaced by a partial index).

## Outcome
_pending_
