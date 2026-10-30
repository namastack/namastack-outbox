# findFailedRecords(lastRetryBefore, limit)

Group G5 (cleanup). SQL `findByStatusAndNextRetryBeforeQuery` + `.withMaxRows(limit)`;
harness `harness/queries/findFailedRecordsBefore.sql` (cutoff = median `next_retry_at` of FAILED rows).

```sql
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at
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
A 0.11 (20), B 0.11, C 0.04, D 0.03, E 0.18; serial the same. Plan: `Sort -> Index Scan idx_status` (A/B/E) or
`Index Scan idx_status_retry` (C/D). Cheap because FAILED rows are few (200-400).

## Hypotheses
- H41 (real `LIMIT`): `Limit -> Index Scan status_retry`; no measurable gain at current FAILED counts, protects a
  failure storm (10^5+ FAILED rows) from a full sort.
- Dependency: if G1/Phase 2 drops `idx_outbox_record_status_retry`, this query falls back to `status` + sort of all
  FAILED rows (linear in FAILED). ADR 0003 explicitly relies on the `(status, next_retry_at)` index.

## Outcome
_pending_
