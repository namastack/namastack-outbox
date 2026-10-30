# deleteFailedRecords(lastRetryBefore, limit)

Group G5 (cleanup). Step 1 `findIdsByStatusAndNextRetryBeforeQuery` + `.withMaxRows(limit)`
(`harness/queries/deleteFailedRecords_selectIds.sql`), step 2 `deleteByIds`.

```sql
SELECT id FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at  -- maxRows = limit
DELETE FROM outbox_record WHERE id IN (:ids)
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
- Semantics: deleting a FAILED head releases the record-key barrier (stop-on-first-failure); documented.

## Baseline (ms (buffers))
select ids: A 0.08 (20), B 0.09, C 0.04, D 0.03, E 0.11 (serial identical). `Sort -> Index Scan idx_status` or
`Index Scan status_retry`. Delete of the (few) ids: sub-ms.

## Hypotheses
- H41 (real `LIMIT`), H42 (single statement). No index change needed while `status_retry` exists.

## Outcome
_pending_
