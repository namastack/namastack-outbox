# deleteCompletedRecords(completedBefore, limit)

Group G5 (cleanup). Step 1 `findIdsByStatusAndCompletedBeforeQuery` + `.withMaxRows(limit)`
(`harness/queries/deleteCompletedRecords_selectIds.sql`), step 2 `deleteByIds` (`harness/queries/deleteByIds.sql`),
in one transaction.

```sql
SELECT id FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at   -- maxRows = limit
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

## Baseline (ms (buffers))
| step | A | B | C | D | E |
|---|---|---|---|---|---|
| select ids, custom | 87.4 (90,939) | 81.7 | 0.03 | 82.8 | 84.8 |
| **select ids, serial = as executed** | **170.2** spill | **159.0** | 0.04 | **156.6** | **170.5** |
| deleteByIds (1000 ids) | 3.15 (3,926) | 2.96 | 0.03 | 3.07 | 3.50 |

Select: parallel seq scan + external sort (5.6 MB). Delete: bitmap scan on the primary key, fine.
The two-step pattern is sound (bounded, primary-key delete, same transaction); its costs are the extra round trip
and the IN-list size limits (Oracle 1000, SQL Server 2100) on `limit` (H42).

## Hypotheses
- **H40** `(status, completed_at)`, **H41** real `LIMIT` (step 1 -> ~1 ms), H42 single-statement bounded delete.

## Outcome
_pending_
