# ADR-0005: Head-of-Key Queries and Incomplete-Record Indexes

- **Status:** Proposed
- **Date:** 2026-10-30
- **Decision owner:** @Alek96
- **Discussion:** To be opened; evidence in [`docs/db/99-final-report.md`](../db/99-final-report.md)
- **Related:** [ADR-0003](0003-retention-of-terminal-records.md), [`docs/db/90-cross-dialect.md`](../db/90-cross-dialect.md)
- **Supersedes:** None

## Context

The poller selects the record keys to process on every poll with
`findRecordKeysInPartitions`. Its SQL groups all due records of the owned partitions by key and
orders the groups by `MIN(created_at)`. A `LIMIT` cannot stop such a query early, so every poll
reads all incomplete records of the owned partitions. On PostgreSQL with one million rows and 20%
`NEW` records it takes 265 ms, and with hot keys the cost grows quadratically per key. The oldest-due
query of the metrics collector has the same shape, and the bulk record load sorts all rows of up to
1000 keys.

The investigation in `docs/db/` measured rewrites and index sets on PostgreSQL 18 and checked them
on MySQL 9.2, MariaDB 12, SQL Server 2022, Oracle XE 18c, JPA (Hibernate) and MongoDB 9. The query
rewrite carries over to every database, but the index that supports it, the row-limit syntax and
some planner details differ per database.

The observability collector also counts `COMPLETED` records every 15 seconds, one query per status.
That count grows with the retained history (24 to 80 ms at one million rows), although the
`outbox.record.attempt` timer already reports the completion rate without a database query.

## Requirements and Constraints

- The rewritten queries return the same rows as before; only the order of keys whose head records
  have equal creation times may change, and it becomes total.
- Correct results do not depend on the new indexes. Installations that keep their old indexes only
  run slower.
- `withMaxRows` stays next to the SQL row limit. Without it, pgjdbc may run a generic plan
  (hot-keys scenario 25 / 145 ms at 64 / 256 partitions).
- Custom `OutboxRecordRepository` implementations keep compiling.
- One repository class per persistence module; no Spring lookups in the dialect selection.
- Non-goal: enforcing the status / `completed_at` invariant with a CHECK constraint.

## Considered Options

### Option 1: Keep the grouped queries and add indexes only

A partial index on the key columns (H02) or a covering partition index (H03) reduces the scanned
rows but cannot stop the grouped query early: −44% in the backlog scenario at best, with up to
+18% insert WAL. Rejected.

### Option 2: Head-of-key queries with one SQL text for all databases

A shared text cannot express the row limit (`LIMIT`, `FETCH FIRST`, `OFFSET … FETCH NEXT`), and a
plain `ORDER BY created_at` on Oracle `TIMESTAMP WITH TIME ZONE` columns never uses index order.
Oracle and MySQL would lose the early stop. Rejected.

### Option 3: Head-of-key queries with per-database SQL and indexes

Each key is represented by its head record: the due record with no earlier incomplete record of the
same key. The query walks incomplete records in `(created_at, id)` order and stops at the batch
size. A per-database query provider supplies the row-limit syntax and planner-specific forms, and
each schema file adds the index that supports the ordered walk on that database.

## Decision

Option 3.

**Queries (JDBC).** `JdbcOutboxRecordQueries` holds the SQL; `JdbcOutboxRecordRepository` only binds
parameters. The database type comes from `JdbcOutboxPersistenceFactory.create(…, databaseType)` or
is detected once from `DatabaseMetaData.getDatabaseProductName()` on the first record query, so
construction opens no connection. Unknown products use portable SQL without a row-limit clause.

| Query | PostgreSQL | MySQL / MariaDB | SQL Server | Oracle | H2 / portable |
|---|---|---|---|---|---|
| keys, previous-failure filter on | head-of-key `NOT EXISTS`, `ORDER BY created_at, id LIMIT` | same, `LIMIT` | same, `OFFSET 0 ROWS FETCH NEXT` | outer `status = ?` without `completed_at IS NULL`, `ORDER BY SYS_EXTRACT_UTC(created_at), id FETCH FIRST` | same as PostgreSQL (`LIMIT` / none) |
| keys, filter off | head-of-key over due `NEW` records | same | grouped form (the rewrite regresses without an index hint) | head-of-key as above | head-of-key |
| oldest due, filter on | head-of-key `ORDER BY next_retry_at LIMIT 1` | same | `OFFSET 0 ROWS FETCH NEXT 1` | `/*+ FIRST_ROWS(1) */`, `ORDER BY SYS_EXTRACT_UTC(next_retry_at) FETCH FIRST 1` | same |
| bulk record load | `unnest(ARRAY[…]) CROSS JOIN LATERAL` per key | `IN` list, `completed_at IS NULL` | same | same | same |
| oldest created | `SELECT created_at … AND completed_at IS NULL ORDER BY created_at LIMIT 1` | same | same, `OFFSET 0 ROWS FETCH NEXT 1` | same, `FETCH FIRST 1` | same (`LIMIT` / none) |
| retention selects | `ORDER BY … LIMIT` | `LIMIT` | `OFFSET 0 ROWS FETCH NEXT` | `FETCH FIRST` | `LIMIT` / none |

**Queries (JPA).** The JPQL key and oldest-due queries use the head-of-key form with
`order by o.createdAt, o.id` and `setMaxResults`; oldest-created selects `o.createdAt` with
`completedAt is null`, `order by o.createdAt` and a row limit of one. The bulk load keeps the `IN`
form. JPQL cannot order by `SYS_EXTRACT_UTC`, so JPA on Oracle returns correct
results without the early stop.

**Queries (MongoDB).** The key query with the previous-failure filter and the oldest-due query use a
head-of-key pipeline: `$match` with `completedAt: null`, `$sort`, a `$lookup` reading the oldest
incomplete record of the key, `$match` on the head id, `$limit`. Oldest-created and the bulk load
add `completedAt: null`. The key query without the filter keeps its grouped pipeline.

**Indexes.**

| Database | Added | Removed |
|---|---|---|
| PostgreSQL | partial `idx_outbox_record_created_id_incomplete (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL`; statistics `outbox_record_status_completed_stx (mcv) ON status, completed_at` | `idx_outbox_record_record_key_created` |
| SQL Server | filtered `idx_outbox_record_created_id_incomplete (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL` | `idx_outbox_record_record_key_created` |
| MySQL, MariaDB, H2 | `idx_outbox_record_completed_created_id (completed_at, created_at, id, partition_no, status, next_retry_at, record_key)` | `idx_outbox_record_record_key_created` |
| Oracle | `idx_outbox_rec_status_created (status, created_at, id, partition_no, next_retry_at, record_key)` | `idx_outbox_rec_key_cr` |
| all relational | `idx_outbox_record_record_key_completed_created_id (record_key, completed_at, created_at, id)` (Oracle `idx_outbox_rec_k_c_c_id`) | `idx_outbox_record_record_key_completed_created` (Oracle `idx_outbox_rec_k_c_c`) |
| JPA `@Table(indexes)` | `idx_outbox_record_record_key_completed_created_id`, non-partial `idx_outbox_record_completed_created_id (completedAt, createdAt, id)` | `idx_outbox_record_record_key_created`, `idx_outbox_record_key_completed_created` |
| MongoDB | partial `incomplete_created_idx {createdAt, _id, partitionNo, status, nextRetryAt, recordKey}` and `incomplete_key_created_idx {recordKey, createdAt, _id}`, both `{completedAt: null}` | `record_key_completed_created_idx`, `fifo_pipeline_idx` |

**Invariant.** The rewrites rely on: a record has status `NEW` or `FAILED` exactly when its
`completed_at` is `NULL`. `OutboxRecord` and the processors maintain it; the schema does not enforce
it. Applications writing to the table directly must keep it.

**Ordering.** Keys are returned in `(created_at, id)` order of their head record. Before, keys with
equal `MIN(created_at)` came in plan-dependent order. The SQL Server and MongoDB key queries without
the previous-failure filter keep the grouped form and its tie order.

**Status counts.** `OutboxRecordRepository.countByStatuses(statuses)` returns a count for every
requested status in one grouped query (default implementation: one `countByStatus` per status). The
observability collector counts only `namastack.outbox.observability.metrics.record-statuses`, by
default `NEW` and `FAILED`, with that single call. The `completed` gauge is opt-in; completion
throughput comes from
`sum(rate(outbox_record_attempt_seconds_count{outbox_processing_outcome="completed"}[5m]))`.
The deprecated `namastack-outbox-metrics` module is unchanged.

## Rationale

On PostgreSQL the hot path (key query, oldest due, 100-key page load, oldest created) drops from
11.6 / 487 / 121 ms to 0.98 / 1.20 / 22.9 ms in the steady-state / backlog / hot-keys scenarios
(measured without the `id`-extended key index). Its
cost scales with the incomplete records of the owned partitions, not with the table size. The key
query in the backlog scenario drops from 265 to 0.21 ms on PostgreSQL, 510 to 2.4 ms on MySQL,
308 to 1.6 ms on MariaDB, 38.6 to 1.0 ms on SQL Server (200k rows), 129.5 to 1.0 ms on Oracle
(200k rows), and 76 to 4 ms on MongoDB. Insert WAL grows by 5.0% on PostgreSQL with the
`id`-extended key index (1.8% without it), updates shrink by 3.7%, and index pages shrink by 16%.
Equivalence was checked with ordered result hashes on five scenarios and an edge seed with ties,
failed heads, future retries and empty partitions; the only differences are the tie order.

Extending the per-key index with `id` lets the per-key probe and the bulk load read a key's records
in `(created_at, id)` order without a sort (hot keys: 22.7 to 8.8 ms for 100 keys).

## Consequences

- Faster polling and metrics collection on every database; the supporting index differs per database.
- `JdbcOutboxPersistenceFactory.create` gains an overload with `databaseType`. The previous signature
  is kept and detects the type on first use.
- Existing installations need manual index changes; automatic schema initialization does not drop
  indexes. The steps below go into the release notes.
- The `completed` record-count gauge is not published by default.
- MySQL, MariaDB and H2 pay for a non-partial index over all rows; on these databases it also serves
  `COMPLETED` retention.

### Upgrade steps for existing installations

1. PostgreSQL:
   ```sql
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_created_id_incomplete
       ON outbox_record (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key)
       WHERE completed_at IS NULL;
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_record_key_completed_created_id
       ON outbox_record (record_key, completed_at, created_at, id);
   CREATE STATISTICS IF NOT EXISTS outbox_record_status_completed_stx (mcv)
       ON status, completed_at FROM outbox_record;
   ANALYZE outbox_record;
   DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_created;
   DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_completed_created;
   ```
   `CONCURRENTLY` cannot run inside a transaction block; run these statements outside migration
   transactions. Automatic schema initialization creates the new indexes without `CONCURRENTLY`,
   which blocks writes while they are built; create them beforehand on large tables.
2. MySQL / MariaDB:
   ```sql
   ALTER TABLE outbox_record
       ADD INDEX idx_outbox_record_completed_created_id
           (completed_at, created_at, id, partition_no, status, next_retry_at, record_key),
       ADD INDEX idx_outbox_record_record_key_completed_created_id (record_key, completed_at, created_at, id),
       ALGORITHM = INPLACE, LOCK = NONE;
   ALTER TABLE outbox_record
       DROP INDEX idx_outbox_record_record_key_created,
       DROP INDEX idx_outbox_record_record_key_completed_created;
   ```
3. SQL Server (`ONLINE = ON` where the edition supports it; the session needs `QUOTED_IDENTIFIER ON`):
   ```sql
   CREATE INDEX idx_outbox_record_created_id_incomplete ON outbox_record (created_at, id)
       INCLUDE (partition_no, status, next_retry_at, record_key, completed_at)
       WHERE completed_at IS NULL WITH (ONLINE = ON);
   CREATE INDEX idx_outbox_record_record_key_completed_created_id
       ON outbox_record (record_key, completed_at, created_at, id) WITH (ONLINE = ON);
   DROP INDEX idx_outbox_record_record_key_created ON outbox_record;
   DROP INDEX idx_outbox_record_record_key_completed_created ON outbox_record;
   ```
4. Oracle:
   ```sql
   CREATE INDEX idx_outbox_rec_status_created
       ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key) ONLINE;
   CREATE INDEX idx_outbox_rec_k_c_c_id ON outbox_record (record_key, completed_at, created_at, id) ONLINE;
   DROP INDEX idx_outbox_rec_key_cr;
   DROP INDEX idx_outbox_rec_k_c_c;
   ```
5. H2: automatic schema initialization creates the new indexes; drop
   `idx_outbox_record_record_key_created` and `idx_outbox_record_record_key_completed_created`.
6. MongoDB: create `incomplete_created_idx` and `incomplete_key_created_idx` (automatic index
   creation or `mongodb-setup.js`), then drop the removed indexes:
   `db.outbox_records.dropIndex("record_key_completed_created_idx")` and
   `db.outbox_records.dropIndex("fifo_pipeline_idx")`.
7. The `idx_outbox_record_record_key_completed_created_id` rebuild is optional for installations that
   want to avoid rebuilding a large index; the old `(record_key, completed_at, created_at)` index
   serves the same queries with an extra sort per key.
8. Metrics: add `completed` to `namastack.outbox.observability.metrics.record-statuses` to keep the
   `outbox_records{outbox_record_status="completed"}` series, or switch dashboards to the
   `outbox.record.attempt` completion rate.

## Follow-up Work

- Decide whether to enforce the invariant with a CHECK constraint
  `(status = 'COMPLETED') = (completed_at IS NOT NULL)`.
- Rerun the SQL Server and Oracle measurements at one million rows; they ran at 200k rows with noisy
  write costs.
- Measure I/O cost: all PostgreSQL runs had the data in memory.
- Offer an index hint for the SQL Server key query without the previous-failure filter.
