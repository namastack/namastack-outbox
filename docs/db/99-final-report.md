# outbox_record indexes and queries: final report

Postgres 18 is the baseline. Unless stated otherwise, numbers are medians of 3 warm runs on 1M rows. Scenarios: A steady state (0.6% NEW), B backlog (20% NEW),
D hot keys (1k keys × 1000), C delete-completed mode, E heavy blocking (section 5.2 of `00-orchestration-prompt.md`). The data directory was on tmpfs, so all
reads were cache hits and no I/O cost is measured. Baseline: [01-baseline.md](01-baseline.md). Per-query matrix: [results/H60-matrix.md](results/H60-matrix.md).
Ledger: [STATE.md](STATE.md).

## 1. Summary

- **The problem.** The key-selection query runs on every poll and today scans all incomplete rows of the table: 265 ms in B at 64 partitions, quadratic per key in D.
- **The fix.** Rewrite it to walk incomplete rows in `(created_at, id)` order with a real SQL `LIMIT`, keeping only the first incomplete record per key (head-of-key).
  It is backed by one small partial index on incomplete rows, plus extended statistics on `(status, completed_at)`.
- **Index changes.** Drop the unused `idx_outbox_record_record_key_created`.
- **Other rewrites.**
  - The bulk record load becomes a per-key ordered LATERAL probe on the existing `(record_key, completed_at, created_at)` index.
  - `findOldestCreatedAtByPartitions` gets `AND completed_at IS NULL`.
  - The cleanup selects get a real SQL `LIMIT`.
- **Gain** ([H60](experiments/H60-consolidated-set.md)):
  - The hot path (key query, oldest-due, 100-key page load, oldest-created) goes from 11.6 / 487 / 121 ms to 0.98 / 1.20 / 22.9 ms in A / B / D, which is −99.8% in B.
  - Hot-path cost now scales with the incomplete rows in the owned partitions, not with the table size.
  - Write cost is +1.8% WAL per insert and −3.7% per update, and index pages shrink by 16%.
- **Portability.** The query rewrite works on every database; the supporting index differs per database ([90-cross-dialect.md](90-cross-dialect.md)).
- **Process.** An ADR is required.

## 2. Recommendation table

Times are ms, before → after. Key and oldest-due queries are serial (pgjdbc `withMaxRows` disables parallel workers); 64 partitions.

| # | Type | Change | Queries affected | Before → after (A / B / D) | Write cost | Portability | Risk | Evidence |
|---|---|---|---|---|---|---|---|---|
| 1 | add index | partial covering `idx_outbox_record_incomplete_created (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL` | key queries, oldest-due (filter), oldest-created | see #3, #5, #6 | with #2: insert WAL +1.8%, update −3.7% | partial: PG, MSSQL (filtered); MySQL/MariaDB/Oracle: non-partial variants (§3) | needs invariant NEW/FAILED ⇔ `completed_at IS NULL` | [H04](experiments/H04-head-of-key-early-stop-limit.md), [H08](experiments/H08-g1-index-set-and-drops.md), [H60](experiments/H60-consolidated-set.md) |
| 2 | drop index | `idx_outbox_record_record_key_created` | none (prefix duplicate of `record_key_completed_created`, 0 scans in A/B/C/E) | no plan change | part of #1 | all dialects; JPA annotation; Mongo also drops two indexes | low | [01-baseline](01-baseline.md), [H10](experiments/H10-key-status-created-index.md), [H16](experiments/H16-unified-per-key-index.md) |
| 3 | rewrite query | `findRecordKeysInPartitions` (filter on): head-of-key `NOT EXISTS` + `ORDER BY created_at, id LIMIT :batch`, no `GROUP BY` | hot path | 5.0 / 265 / 38 → 0.22 / 0.21 / 0.09 (256p: 19 / 367 / 141 → 0.34 / 0.38 / 0.11) | none | LIMIT / FETCH FIRST / TOP; Oracle needs `SYS_EXTRACT_UTC` ordering | tie order among keys with equal `created_at` becomes `(created_at, id)`; today it depends on the plan | [H04](experiments/H04-head-of-key-early-stop-limit.md), [H06](experiments/H06-probe-on-existing-key-completed-index.md), [H60](experiments/H60-consolidated-set.md) |
| 4 | rewrite query | `findRecordKeysInPartitions` (filter off): same head-of-key form over due NEW rows | hot path (non-default) | 1.3 / 56 / 4.5 → 0.15 / 0.13 / 0.09 | none | as #3; SQL Server needs an index hint on the probe or keeps the current query | without #7, D at 256p flips to 16 ms | [H05](experiments/H05-forced-head-probe-filter-off.md), [H60](experiments/H60-consolidated-set.md) |
| 5 | rewrite query | `findOldestDueNextRetryAtByPartitions` (filter on): scalar head-of-key `ORDER BY next_retry_at LIMIT 1` | periodic | 4.7 / 165 / 39 → 0.18 / 0.15 / 0.06 | none | Oracle: `FIRST_ROWS(1)` hint | low | [H04](experiments/H04-head-of-key-early-stop-limit.md), [H08](experiments/H08-g1-index-set-and-drops.md) |
| 6 | rewrite query | `findOldestCreatedAtByPartitions`: add `AND completed_at IS NULL` | periodic | 1.0 / 50 / 4.6 → 0.067 / 0.065 / 0.033 | none | PG, MSSQL, Mongo; MySQL/MariaDB use `ORDER BY created_at LIMIT 1` | callers pass NEW only; value is wrong for COMPLETED (not used) | [H32](experiments/H32-partition-metrics-on-incomplete-partial-index.md) |
| 7 | add statistics | `CREATE STATISTICS outbox_record_status_completed_stx (mcv) ON status, completed_at` | filter-off key query | D 256p 16.1 → 0.10 | none (ANALYZE only) | PG only; not needed elsewhere | low | [H05](experiments/H05-forced-head-probe-filter-off.md), [H60](experiments/H60-consolidated-set.md) |
| 8 | rewrite query | `findIncompleteRecordPages`: `unnest(keys) CROSS JOIN LATERAL (… WHERE record_key = k AND status = 'NEW' AND completed_at IS NULL ORDER BY completed_at, created_at, id)`, no top `ORDER BY` | hot path | 100 keys 0.84 / 6.6 / 40 → 0.52 / 0.77 / 22.7; 1000 keys 4.0 / 22 / 40 → 4.7 / 8.1 / 22 (no sort spill) | none | PG (MySQL possible); others keep IN | A at 1000 keys +16% (4.0 → 4.7) | [H14](experiments/H14-lateral-per-key-ordered-probe.md), [H60](experiments/H60-consolidated-set.md) |
| 9 | rewrite query | cleanup selects (`findCompleted/FailedRecords(cutoff, limit)`, `deleteCompleted/FailedRecords` id select): real SQL `LIMIT` with `ORDER BY completed_at` | cleanup (application SPI) | 157–273 → 72–113 (no index) | none | LIMIT / FETCH FIRST / TOP | none; equivalence exact | [H41](experiments/H41-cleanup-sql-limit.md) |
| 10 | modify index (optional) | `record_key_completed_created` → `(record_key, completed_at, created_at, id)` | pages (hot keys) | pages 100 keys D 22.7 → 8.8, B 0.77 → 0.59 | insert WAL +3.2 pp, +63 MB | all (non-partial) | index rebuild on large tables | [H16](experiments/H16-unified-per-key-index.md), [H60](experiments/H60-consolidated-set.md) |
| 11 | keep | `withMaxRows(batch)` next to the SQL `LIMIT` | key queries | forced-generic plan without it: D 25 / 145 ms (64p / 256p) | none | n/a | low | [H60](experiments/H60-consolidated-set.md) F-2 |

All rows: equivalence passes on A–E plus the edge seed (ties, FAILED heads, mixed `next_retry_at`, empty partitions) with 0 FAIL. The only differences are the tie order noted in #3.

## 3. Index set

### Postgres (current → proposed)
| Index | Current | Proposed |
|---|---|---|
| `pkey (id)` | ✓ | ✓ |
| `idx_outbox_record_record_key_created (record_key, created_at)` | ✓ | **drop** |
| `idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` | ✓ | ✓ (optional: add `id`, #10) |
| `idx_outbox_record_status_retry (status, next_retry_at)` | ✓ | ✓ (oldest-due, `findFailedRecords(cutoff)`) |
| `idx_outbox_record_status (status)` | ✓ | ✓ (`countByStatus`; dropping it: COMPLETED 24 → 42–76 ms) |
| `idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)` | ✓ | ✓ (partition counts; dropping it: B 4/17 → 47/49 ms) |
| `idx_outbox_record_incomplete_created` (partial, covering) | – | **add** |
| statistics `(mcv) ON status, completed_at` | – | **add** |

Sizes in A: index pages go from 43,374 to 36,564 (−16%). The new partial index is about 20 MB in B. ([H60](experiments/H60-consolidated-set.md))

### Other databases ([90-cross-dialect.md](90-cross-dialect.md))
| DB | Add | Drop | Notes |
|---|---|---|---|
| MySQL / MariaDB | `(completed_at, created_at, id, partition_no, status, next_retry_at, record_key)` | `record_key_created` | `id` must be explicit; this index also serves cleanup |
| SQL Server | filtered `(created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL` | `record_key_created` | needs `QUOTED_IDENTIFIER ON`; about write-neutral; cleanup index optional |
| Oracle | `(status, created_at, id, partition_no, next_retry_at, record_key)` | `record_key_created` | the status-based predicate (user idea) wins here; requires `SYS_EXTRACT_UTC` ordering |
| JPA `@Table(indexes)` | non-partial `(completedAt, createdAt, id)` (ddl-auto/dev only) | `idx_outbox_record_record_key_created` | the real indexes come from the DDL files; fix the index name mismatch |
| Mongo `@CompoundIndexes` | partial `{createdAt, _id, partitionNo, status, nextRetryAt, recordKey}` and `{recordKey, createdAt, _id}`, `partialFilter {completedAt: null}` | `fifo_pipeline_idx`, `record_key_completed_created_idx` | Mongo B 76 → 4 ms; dropped indexes need a manual drop |

## 4. Query rewrites (Postgres)

Key query with the previous-failure filter. The current form is `… AND NOT EXISTS (older incomplete record of the same key) GROUP BY o.record_key ORDER BY MIN(o.created_at)`.
```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at
      AND (older.created_at < o.created_at
           OR (older.created_at = o.created_at AND older.id < o.id)))
ORDER BY o.created_at, o.id
LIMIT :batch
```
- **Filter off:** the same query, with the probe restricted to `earlier.status = :status AND earlier.next_retry_at <= :now`. It returns the first due NEW record per key.
- **Oldest-due (filter on):** the same `WHERE` in a scalar subquery with `ORDER BY o.next_retry_at LIMIT 1`.
- **Why the result is unchanged:**
  - At most one row per key survives: the first incomplete record for filter on, the first due NEW record for filter off.
  - Its `created_at` equals `MIN(created_at)` of the key's due NEW records.
  - Ordering by `(created_at, id)` therefore reproduces the original order, and also makes the tie order total.
- **Evidence:**
  - Phase 1: exact ordered md5 on A–E for 64/256 partitions and filter on/off (140/140 PASS in [H04](experiments/H04-head-of-key-early-stop-limit.md)).
  - Phase 2: 33/33 per scenario, plus the edge seed (72 PASS, 14 PASS-TIES, 0 FAIL) ([H60](experiments/H60-consolidated-set.md)).

Bulk page load. The current form is `WHERE record_key IN (:keys) AND status = :status ORDER BY record_key, created_at, id`.
```sql
SELECT o.* FROM unnest(CAST(:keys AS varchar[])) AS k(k)
CROSS JOIN LATERAL (
  SELECT r.* FROM outbox_record r
  WHERE r.record_key = k.k AND r.status = 'NEW' AND r.completed_at IS NULL
  ORDER BY r.completed_at, r.created_at, r.id) o
```
- **Ordering:** the consumer needs only the per-key `(created_at, id)` order. Kotlin's `groupBy` keeps encounter order, and page order comes from the requested set.
- **Status binding:** the code always passes NEW, so it can be inlined as a literal.
- **Evidence:** the output was even byte-identical to the original ([H14](experiments/H14-lateral-per-key-ordered-probe.md)). A negative control, a NEW row with `completed_at` set, FAILs as expected.

Other rewrites:
- **Oldest-created:** `SELECT MIN(created_at) … WHERE partition_no IN (…) AND status = :status AND completed_at IS NULL`.
- **Cleanup:** `… WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit`. Equivalence is exact for limit 1, 1000 and 100000 ([H41](experiments/H41-cleanup-sql-limit.md)).

Every rewrite relies on the invariant that a record's status is NEW or FAILED exactly when its `completed_at` is NULL. This holds in `OutboxRecord` and in the processors, and it was verified in [H09](experiments/H09-status-based-incomplete-predicate.md). The schema does not enforce it.

## 5. Rejected ideas

- **H01:** a sargable `created_at` bound alone. No plan change on PG. ([H01](experiments/H01-sargable-predecessor-bound.md))
- **H02:** partial key index `(record_key, created_at, id) WHERE completed_at IS NULL`. Only −44% in B, and H06 reaches the same with the existing index. ([H02](experiments/H02-partial-incomplete-key-index.md))
- **H03:** covering partial partition index. Dominated by the head-of-key form, insert WAL +16–18%, and A at 256p regresses. ([H03](experiments/H03-covering-partition-incomplete-index.md))
- **H05 S/F:** scalar-subquery or `OFFSET 0` fence for filter off. D 392–2369 ms or 15–72 ms. ([H05](experiments/H05-forced-head-probe-filter-off.md))
- **H07 on PG:** non-partial `(completed_at, created_at, id)`. No gain in A/E, 117 MB covering. It remains the cleanup index users can add. ([H07](experiments/H07-nonpartial-completed-created-outer-index.md))
- **H08b:** keeping both partial indexes. +9% insert WAL for no gain. ([H08](experiments/H08-g1-index-set-and-drops.md))
- **H09 on PG** (user idea: the status-based "incomplete" predicate): with partial indexes, A/E regress (IN form) or it equals H04 (`<>` form). With non-partial indexes it needs a 130 MB outer index, +9%/+12% WAL. Stacking `status IN`/`<>` on `status = :status` keeps the misestimate. It is the best option on Oracle. ([H09](experiments/H09-status-based-incomplete-predicate.md))
- **H10:** `(record_key, status, created_at, id)`. +7–9% WAL, and the Lon form on the existing index gets most of the gain at zero DDL. ([H10](experiments/H10-key-status-created-index.md))
- **H11:** `completed_at IS NULL` alone in the IN pages query. New spill at 1000 keys. ([H11](experiments/H11-completed-at-null-predicate.md))
- **H12:** H02 index with the IN pages query. The planner rejects the ordered scan (estimate 42 vs 19k rows). ([H12](experiments/H12-partial-incomplete-key-index.md))
- **H13:** `= ANY(array)` instead of an IN list. Custom plans ±5%; only generic planning time improves. ([H13](experiments/H13-any-array-parameter.md))
- **H14 L/Ln:** LATERAL with `status = 'NEW'` only. BitmapAnd per key, A up to 148 ms. ([H14](experiments/H14-lateral-per-key-ordered-probe.md))
- **Status predicates added to the pages query:** no help, and the IN form worsens generic plans (A 1000 keys 228 ms). ([H14](experiments/H14-lateral-per-key-ordered-probe.md))
- **H15:** status-predicate partial key index. Not run; the user's idea concerns query predicates. ([H15](experiments/H15-status-based-partial-key-index.md))
- **H30:** replacing or dropping `idx_status`. `countByStatus(COMPLETED)` goes 24 → 74 ms (445 with a dirty visibility map). ([H30](experiments/H30-countbystatus-status-index-guard.md))
- **H31:** `(status, created_at)` replacing `idx_status`. Dominated by H32, and the same count penalty. ([H31](experiments/H31-status-created-oldest-created.md))
- **H32 for the partition counts:** a full partial-index scan is slower than `partition_status_retry` (B 4 → 17 ms, generic 71/202). ([H32](experiments/H32-partition-metrics-on-incomplete-partial-index.md))
- **H40 as a default index:** `(status, completed_at)`. Without a SQL LIMIT the plan flips (154–335 ms), and once added it flips the A key query 0.2 → 5 ms. ([H40](experiments/H40-status-completed-index.md))
- **H42:** single-statement bounded delete. Same DB time as select + delete. ([H42](experiments/H42-single-statement-bounded-delete.md))
- **Dropping `partition_status_retry`, `status` or `status_retry` after the changes:** count or oldest-due penalties of 3–10×. ([H60](experiments/H60-consolidated-set.md))
- **Forced generic plans:** never chosen by pgjdbc in practice (auto mode stayed custom), and much worse on the baseline (up to 872 ms). ([01-baseline](01-baseline.md))
- **Cross-dialect:**
  - SQL Server `CROSS APPLY (VALUES …)` drops rows at 299 or more keys.
  - Oracle `CROSS APPLY` fails at 1000 keys (ORA-00939).
  - A plain `ORDER BY created_at` on Oracle never stops early.
  - ([90-cross-dialect](90-cross-dialect.md))

## 6. Per-query matrix (Postgres, S1, ms, A / B / D, 64 partitions unless noted)

| Method | Class | Index before → after | Before | After |
|---|---|---|---|---|
| `findRecordKeysInPartitions` filter on | hot | status (+ hash anti join, seq scan in B) → incomplete_created + key_completed_created probe | 5.0 / 265 / 38 | 0.22 / 0.21 / 0.09 |
| same, 256p | hot | same | 19 / 367 / 141 | 0.34 / 0.38 / 0.11 |
| `findRecordKeysInPartitions` filter off | hot | status → incomplete_created + probe | 1.3 / 56 / 4.5 | 0.15 / 0.13 / 0.09 |
| `findIncompleteRecordPages` 100 / 1000 keys | hot | status bitmap + sort (spill in D) → key_completed_created per key | 0.84/4.0, 6.6/22, 40/40 | 0.52/4.7, 0.77/8.1, 22.7/22.2 |
| `findIncompleteRecordsByRecordKey` | admin | key_completed_created | 0.28 / 0.05 / 1.06 | unchanged |
| `create`, `updateProcessingState` | hot | – | < 0.05 per statement | unchanged; WAL insert +1.8%, update −3.7% |
| `findOldestDueNextRetryAtByPartitions` filter on | periodic | status + anti join → incomplete_created + probe | 4.7 / 165 / 39 | 0.18 / 0.15 / 0.06 |
| `findOldestDueNextRetryAtByPartitions` filter off | periodic | status_retry | 0.04 | unchanged |
| `findOldestCreatedAtByPartitions` | periodic | status (partition as filter) → incomplete_created | 1.0 / 50 / 4.6 | 0.067 / 0.065 / 0.033 |
| `countByStatus` NEW / FAILED / COMPLETED | periodic | status | 0.33/9.9/1.0, 0.04, 24/20/23 | unchanged |
| `countRecordsByPartitions` 64 / 256 | periodic | partition_status_retry | B 4.2 / 16.8 | unchanged |
| `countRecordsByPartition` | periodic (deprecated) | partition_status_retry | ≈ 0.1 | unchanged |
| `findCompletedRecords(cutoff, limit)` | cleanup | seq scan + spilling sort → seq scan + top-N | 273 / 211 / 253 | 77 / 78 / 73 (cutoff 10%) |
| `deleteCompletedRecords` id select | cleanup | same | 170 / 159 / 157 | 76 / 75 / 72 |
| `findFailedRecords(cutoff, limit)`, `deleteFailedRecords` | cleanup | status_retry / status | ≈ 0.1 | unchanged |
| `findPendingRecords` | admin | status | 3.1 / 212 / 23 | unchanged |
| `findCompletedRecords()`, `deleteByStatus(COMPLETED)` | admin | seq scan | 888 / 710 / 849; 737 / 620 / 752 | unchanged |
| `findFailedRecords()`, `deleteByStatus(FAILED)`, `deleteByRecordKeyAndStatus`, `deleteById(s)` | admin | status / key / pkey | ≤ 3 | unchanged |

Source: [results/H60-matrix.md](results/H60-matrix.md); baseline [01-baseline.md](01-baseline.md).

Targets (section 6.1):
- **No hot-path seq scans:** met, in custom and generic plans, in every scenario.
- **Scaling with incomplete rows:** met. B has 33× the incomplete rows of A and costs 1.20 vs 0.98 ms.
- **Hot path in B −50%:** met, at −99.8%.
- **Write cost ≤ +15%:** met, at +1.8% / −3.7%.
- **Visibility map:** after 50k updates without VACUUM, the hot path stays below 0.6 ms with no cliff.

## 7. Migration notes

- **Schema.** Change every dialect under `namastack-outbox-jdbc/src/main/resources/schema/` as in §3, plus the JPA `@Table(indexes)` and Mongo `@CompoundIndexes`.
  Integration tests validate the schemas, so update them together.
- **Existing installs.**
  - **Postgres:** `CREATE INDEX CONCURRENTLY` for the partial index. It is small, since it holds only incomplete rows. Run `DROP INDEX CONCURRENTLY` for `record_key_created`.
    After `CREATE STATISTICS`, run `ANALYZE outbox_record`. The DDL files use `IF NOT EXISTS`, but `CONCURRENTLY` cannot run inside a transaction, so document it as a manual step.
  - **SQL Server:** use `ONLINE = ON` where the edition supports it.
  - **MySQL/MariaDB:** InnoDB online DDL (`ALGORITHM=INPLACE, LOCK=NONE`). This index is non-partial and builds over the whole table.
  - **Oracle:** `CREATE INDEX … ONLINE`.
  - **Mongo:** dropped indexes are not removed by Spring Data; give users a manual drop step.
- **Optional #10** rebuilds an existing index, so offer it as an opt-in for hot-key workloads.
- **Code.**
  - `JdbcOutboxRecordRepository` has no dialect-specific SQL today. The limit clause, Oracle's `SYS_EXTRACT_UTC` ordering, Oracle's `FIRST_ROWS(1)` hint, the
    MySQL/MariaDB oldest-created form and the Postgres-only LATERAL pages query need a dialect abstraction.
  - Keep `withMaxRows(batch)` next to the SQL limit (#11).
  - JPA: rewrite the JPQL (head-of-key with `setMaxResults`; oldest-created). The pages query keeps the IN form.
  - Mongo: head-of-key aggregation pipeline.
- **ADR required.** This affects the persistence model, ordering semantics (tie order becomes total), several modules and every dialect. Use `docs/adr/0000-template.md`
  and follow `GOVERNANCE.md`. The ADR should state the NEW/FAILED ⇔ `completed_at IS NULL` invariant as a constraint, because the rewrites depend on it.
- **Release notes.** List the new and dropped indexes, the manual online-migration steps per database, the optional #10, and that cleanup users can add an index
  (`(completed_at, created_at, id)` or `(status, completed_at)`) as already described in the retention docs and ADR 0003.

## 8. Open questions and follow-ups

1. **Invariant.** Should the invariant be enforced, with a CHECK constraint `(status = 'COMPLETED') = (completed_at IS NOT NULL)`? It would also let planners reason about it.
2. **`findIncompleteRecordsByRecordKey` KDoc.** It says "all incomplete records", but the query returns only NEW. Fix either the doc or the query ([queries/findIncompleteRecordsByRecordKey.md](queries/findIncompleteRecordsByRecordKey.md)).
3. **`countByStatus(COMPLETED)`.** It grows linearly with history (24–80 ms at 1M rows) and runs every 15 s from the observability collector for all three statuses. The deprecated metrics module
   calls it on every scrape, and also runs `countRecordsByPartition` 4 × owned partitions per scrape. Consider dropping or caching the COMPLETED gauge, or adding a grouped `countByStatuses` SPI ([H30](experiments/H30-countbystatus-status-index-guard.md), [H32](experiments/H32-partition-metrics-on-incomplete-partial-index.md)).
4. **Not measured.**
   - I/O cost: all Postgres runs were on tmpfs, so cache misses were not measured.
   - Whether MySQL Connector/J `maxRows` maps to a real limit.
   - JPA plans on a real database: they were derived from the equivalent SQL.
5. **Partial-scale dialects.** SQL Server and Oracle ran at 200k rows, and their write cost was noisy. Rerun at 1M rows on a host with more disk before the ADR is finalised.
6. **IN-list size.** A 1000-key page is exactly at Oracle's IN-list limit. H42's single-statement delete would remove this limit for cleanup, but has no DB-time gain.
7. **SQL Server filter-off key query.** It needs an index hint, or the current query must stay; decide in the implementation.
8. **Dialect strategy.** One shared SQL text across databases would cost Oracle and MySQL their early stop. The implementation needs a per-dialect query provider.
