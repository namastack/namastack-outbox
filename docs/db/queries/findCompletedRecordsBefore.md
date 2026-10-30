# findCompletedRecords(completedBefore, limit)

Group G5 (cleanup). SQL `findByStatusAndCompletedBeforeQuery` + `.withMaxRows(limit)`;
harness `harness/queries/findCompletedRecordsBefore.sql` (cutoff = 10th percentile of `completed_at`, ~99k rows).

```sql
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at
```

## Callers and frequency
- **No caller in the library main code** (verified: grep over all modules, examples and actuator; references only in
  the SPI, the three repository implementations, their tests, `docs/adr/0003-retention-of-terminal-records.md` and
  `namastack-outbox-docs/docs/reference/retention.md`). Retention SPI for application jobs (`@Scheduled`, ShedLock,
  ...), ADR 0003 Option A: no built-in scheduler. Frequency = whatever the application chooses; the documented
  usage is a loop "call until it returns less than `limit`", so the select runs once per batch.
- The property `namastack.outbox.processing.delete-completed-records` is unrelated: it calls `deleteById` per record.
- JPA uses `setMaxResults(limit)` (real SQL limit via the Hibernate dialect); Mongo uses `.limit(limit)`.
  Only JDBC uses `.withMaxRows(limit)`, which the planner does not see and which disables parallel workers.

## Baseline (ms (buffers))
| mode | A | B | C | D | E |
|---|---|---|---|---|---|
| custom (workers) | 140.8 (90,939) spill | 125.6 | 0.04 | 136.8 | 142.4 |
| **serial = as executed** | **273.2** spill | **211.2** | 0.03 | **252.8** | **269.7** |

Plan: `Gather Merge -> Sort (external merge, 65 MB) -> Parallel Seq Scan` (900k rows removed). No index on
`completed_at`; cost is independent of `limit` because the Sort consumes everything.

## Hypotheses
- **H40** `(status, completed_at)` (add, or replace `idx_status`): ordered index scan, streaming; as executed with
  `withMaxRows` ~0.5-2 ms for limit 1000 (if the planner picks the index without seeing the limit; risk at large cutoffs).
- **H41** real `LIMIT`: with H40 ~1 ms at every cutoff; without the index top-N heapsort + parallel seq scan ~40-80 ms.
- Tie note: `ORDER BY completed_at` has no tie-breaker (contract does not require one).

## Outcome
_pending_
