# MariaDB outbox table: design c-1 versus design c-2

## 1. Context

The library stores messages that still have to be delivered in one table, `outbox_record`. Every row has a `record_key`
(rows with the same key must be delivered in order, one after another), a `status` (`NEW` = waiting, `COMPLETED` = delivered,
`FAILED` = gave up), `created_at`, `next_retry_at`, `partition_no` and `completed_at` (the time of delivery, NULL while the
row is not delivered). Keys are hashed into 256 partitions; each application instance owns a subset of them ("owned
partitions"). With "partial ownership" an instance owns only some partitions (for example 64 of 256, or 16 of 256), which is
the normal deployment.

Three kinds of queries matter. The "hot" polling queries find the next record keys to process among `NEW` rows in the owned
partitions, repeatedly and with a batch size of 10. The monitoring queries ask for the oldest waiting row or the oldest due
row, and for counts per status or per partition. The retention delete removes `COMPLETED` rows older than some age, in batches.
A "secondary index" is an extra sorted copy of some columns that lets MariaDB find rows quickly, but every insert, update and
delete must also maintain it. "Write cost" is measured as redo bytes: the bytes MariaDB writes to its transaction log per
single write; more indexes touched means more bytes.

## 2. Variant c-1

Four secondary indexes on `outbox_record`:

1. `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`
2. `(record_key, completed_at, created_at, id)`
3. `(status, completed_at, next_retry_at, partition_no)`
4. `(status, partition_no, created_at)`

The hot queries use a "head form": scan candidate `NEW` rows of the owned partitions in age order, and keep a row only if it
is the first unfinished row of its key (looked up through index 2). Example, `findStrictRecordKeys`:

```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND o.id = ( SELECT e.id FROM outbox_record e
               WHERE e.record_key = o.record_key AND e.completed_at IS NULL
               ORDER BY e.created_at, e.id LIMIT 1 )
ORDER BY o.created_at, o.id LIMIT :limit
```

Idea in plain words: every query states both "status is NEW" and "`completed_at` is NULL", and the indexes contain both columns,
so the queries do not rely on the two always agreeing.

## 3. Variant c-2

Also four secondary indexes, the same except that `completed_at` is removed from indexes 1 and 3:

1. `(status, created_at, id, partition_no, next_retry_at, record_key)`
2. `(record_key, completed_at, created_at, id)` (unchanged)
3. `(status, next_retry_at, partition_no)`
4. `(status, partition_no, created_at)` (unchanged)

The queries are the same as in c-1 but without the outer `AND o.completed_at IS NULL` (in the example above, the third line of
the WHERE clause disappears). The inner lookup keeps `e.completed_at IS NULL`.

Idea in plain words: `status = NEW` already says "not delivered", so `completed_at` is redundant in the indexes; dropping it
makes them smaller.

## 4. The difference

- Indexes 1 and 3 have no `completed_at` column in c-2.
- The hot and monitoring queries have no outer `completed_at IS NULL` condition in c-2.
- c-2 therefore relies on this assumption: **a record is `NEW` exactly when `completed_at` is NULL** (more precisely: for the
  statuses the code queries, `completed_at IS NULL` and the status agree). If a row ever violated that, c-2 and c-1 could
  return different rows.
- The study owner decided this assumption is acceptable. It was not used to pick a winner.
- Side effect: c-2 has no index that starts with `(status, completed_at)`, which the retention delete uses (section 5).

## 5. Outcome of the experiment

**Setup.** MariaDB 12.3.3, generated data. Each variant (and the current branch design, "c-ref", as a baseline) ran on the same
generated tables, twice, in reversed order with a database restart in between; the numbers are medians of 20 repetitions after
5 warm-ups. Scenarios shown here:

- S2: 1M rows, mostly `COMPLETED`, about 1,000 due `NEW` rows, one instance owns all partitions.
- S4: 1M `NEW` rows backlog plus 1M `COMPLETED`, one instance owns all partitions.
- S5: 500,000 rows: 100 hot keys with 2,000 waiting `NEW` rows each, plus 300,000 older `COMPLETED` rows of the same keys; some keys are blocked by a failed or not-yet-due first row.
- S6: 1M rows: 800,000 `COMPLETED`, 195,000 `NEW` rows scheduled for a retry 1 minute to 1 hour in the future, 5,000 `NEW` rows due now.
- S8-S2, S8-S4, S8-S5, S8-S6: the same data, but the instance owns 64 of 256 partitions (partial ownership). S16-S4: 16 of 256.
- S9: churn: 500,000 inserts and completions, then the retention delete of about 500,000 old `COMPLETED` rows with a 2,700 s time budget.
- S10: processing loop under concurrent inserts, 60 s per run, 1 and 4 pollers, with and without throttled inserts.

**Correctness.** Both variants returned the same rows as the reference on all checked scenarios (S1, S2, S5, S7): all reads and
write effects agreed, no failures.

**Noise.** Differences under 11 % on the hot queries and 10 % on the monitoring queries (0.04 ms absolute under 0.3 ms) are
treated as noise. Write-cost differences under 12 % are noise.

| Measure | c-1 | c-2 | c-2 vs c-1 |
|---|---|---|---|
| Hot queries (`keys_strict`, `keys_nonstrict`, `pages`), all scenarios | 0.4-1.1 ms; S8-S5 `keys_strict` 54 ms | same (S8-S5 50-51 ms) | equal within noise |
| `oldest_due_*` (monitoring) | 0.2-0.6 ms | same | equal within noise |
| `oldest_created`, partial ownership | S8-S2 0.21, S8-S4 0.25, S8-S5 0.21, S8-S6 0.22 ms | 0.31, 0.38, 0.33, 0.34 ms | c-2 about 0.1 ms slower |
| `count_statuses` | S2 2.5, S6 23.6, S8-S6 23.3 ms | 2.7, 25.7, 25.5 ms | c-2 +6 to +19 % slower |
| `count_partitions`, full ownership | S5 27.1, S6 27.0 ms | 25.3, 31.1 ms | -7 % to +14 %, mixed |
| Index size at load | S2 347 MB, S4 642 MB | 336 MB, 630 MB | c-2 -3 % / -2 % |
| Write cost, S2 completion (redo bytes per write) | 8,708 / 8,461 (two passes) | 9,665 / 8,823 | c-2 +4 to +11 %, near the noise limit |
| Write cost, S2 delete | 5,784 / 5,700 | 6,021 / 6,195 | c-2 +4 to +9 %, within noise |
| Write cost, insert, retry, fail | equal | equal | equal |
| S10 throughput (6 runs) | e.g. 1,323 rec/s (S2, 4 pollers, unthrottled) | 1,297 / 1,269 | equal within 10 % |
| S9 retention rows removed within 2,700 s | 500,000 (in 286 s) | 26,000 (stopped at the 2,700 s budget) | c-2 far slower |

Retention in c-2 is slow because its indexes cannot serve `WHERE status = 'COMPLETED' AND completed_at < ? ORDER BY completed_at`,
so MariaDB sorts all `COMPLETED` rows for every batch (about 5 s per batch versus well under 1 s in c-1). The same weakness
exists in the older `main` design (28,000 rows in the budget). Retention runs in the background and has a lower priority than
the polling and monitoring queries, but it decides whether old rows are removed in time, so it counted in the choice.

For context, both variants fix the severe slowdowns of the current branch design (c-ref) at full ownership (S4, S5, S6: oldest
and key queries went from over 10 s to under 1 ms) and at partial ownership (S8-S4 `oldest_created` 163 ms to about 0.25 ms; S16-S4
5 s to under 0.2 ms), and both are clearly faster than `main` where `main` collapses. Both pay more write cost than the branch
design for the fourth index.

## 6. Summary and recommendation

On the polling queries, write cost and concurrent throughput the two variants are equal within noise. c-2 has smaller indexes
(2-3 %), which is its only advantage. c-1 is slightly faster on `oldest_created` (about 0.1 ms at partial ownership) and on
`count_statuses` (6-19 %) and keeps a `completed_at` index, so the retention delete stays fast (500,000 rows in 286 s against
26,000 rows in 2,700 s). c-1 also does not depend on the "`NEW` exactly when `completed_at` is NULL" assumption, but that was
deliberately not used as a reason: the assumption is accepted as a rule of the library.

**Recommendation: c-1.** The size saving of c-2 is small, while its monitoring queries are slightly slower and its retention
delete degrades badly. If only the polling and monitoring queries counted, the two would be equal and c-2 would be the
smaller schema.
