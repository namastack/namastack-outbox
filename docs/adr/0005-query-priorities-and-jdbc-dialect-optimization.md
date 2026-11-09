# ADR-0005: Query Priorities and Per-Dialect JDBC Optimization

- **Status:** Accepted
- **Date:** 2026-11-04
- **Decision owner:** @Alek96
- **Discussion:** No GitHub Discussion recorded
- **Related:** [ADR-0003](0003-retention-of-terminal-records.md)
- **Supersedes:** None

## Context

Namastack Outbox implements the transactional outbox pattern. Its purpose is reliable
message delivery: a record is written atomically with the business data and then delivered
asynchronously. The database work that decides how well the library serves this purpose is
the processing loop, and the work that tells operators whether it is healthy is the metrics
collection.

The `outbox_record` table carries indexes and SQL that serve every query equally, including
administration and cleanup. Every index adds cost to each insert and state update in the
processing loop. `COMPLETED` records are kept by default (see ADR-0003), so indexes that
cover them grow with the retained history rather than with the pending work.

Optimizing needs an explicit rule for which queries matter, because an index that helps one
query group is paid for by the writes of another.

## Requirements and Constraints

- Delivery semantics stay unchanged: per-key ordering, retries, and failure handling return
  the same results on every database.
- Multi-instance deployment is the expected setup, so an instance normally owns only part
  of the partitions. Results for partial ownership rank above full ownership when options
  are compared.
- Non-goal: making retention and administration queries fast.
- Non-goal: changing the column types or the record ID format.

## Query Priorities

**Priority 1: scheduling and execution.** The hot path, including its writes:

- Record key selection: `findRecordKeysInPartitions`, strict and non-strict
- Bulk record loading: `findIncompleteRecordPages`
- Writes: `create`, `updateProcessingState` (complete, retry, fail), and `deleteById`
  (delete on completion)
- Concurrent processing throughput

**Priority 2: metrics of the observability collector:**

- `countByStatuses` for `NEW` and `FAILED`
- `countRecordsByPartitions`
- `findOldestCreatedAtByPartitions`
- `findOldestDueNextRetryAtByPartitions`

The deprecated metrics module (`countByStatus`, `countRecordsByPartition`) is not a target.

**Priority 3: retention, cleanup, and administration:**

- `findCompletedRecords`, `findFailedRecords`, `findPendingRecords`
- `deleteCompletedRecords`, `deleteFailedRecords`, `deleteByIds`
- `deleteByStatus`, `deleteByRecordKeyAndStatus`
- `findIncompleteRecordsByRecordKey`

These queries need correct SQL, and a SQL row limit where they are bounded. They may be slow.

## Considered Options

### Query scope

**Option 1: Optimize all query groups equally.** Every query keeps a supporting index.
Retention and administration stay fast, but processing pays for indexes that it does not use.

**Option 2: Optimize processing and metrics first.** Indexes are justified only by Priority 1
and 2 queries. Priority 3 queries become slower in exchange for cheaper processing.

### Query implementation

**Option A: One portable query set for JDBC and JPA.** This is the simplest to maintain, but
it cannot use features such as PostgreSQL `unnest` with `LATERAL`, or dialect-specific
ordering on Oracle.

**Option B: Per-dialect queries for both JDBC and JPA.** JPA would have to bypass JPQL with
native queries for each database, which removes much of what the JPA module offers.

**Option C: Per-dialect queries for JDBC, the best portable queries for JPA.**

## Decision

Option 2 for query scope and Option C for query implementation.

1. **Index rule.** An index is justified only by Priority 1 or 2 queries, and only if its read
   benefit outweighs its write cost: insert and update latency, WAL volume, index size, and
   bloat. An index that serves only Priority 3 is dropped. Removing indexes to make processing
   faster is an explicit goal.
2. **Indexes and SQL change.** The `outbox_record` indexes are replaced by a set built around
   incomplete records. All databases, JPA included, select incomplete records by status alone and
   use status-leading composite indexes without `completed_at`. Checks for earlier records of a
   key keep `completed_at IS NULL` everywhere, because `FAILED` records also block their key.
3. **`COMPLETED` is not counted in metrics by default.**
   `namastack.outbox.observability.metrics.record-statuses` defaults to `NEW` and `FAILED`,
   collected with one grouped query per refresh. Applications that need the `COMPLETED` gauge
   opt in and accept a query whose cost grows with the retained history.
4. **JDBC optimizes per dialect.** JDBC has one query class per database on top of a portable
   default class. A dialect may change the execution plan and the row-limit syntax, but not
   the result.
5. **JPA uses the best default queries.** JPA has one portable JPQL query set with the same
   logic as the JDBC default queries, and no per-dialect variants.

## Rationale

The library exists to deliver messages, so the cost of delivery comes first, and operators
need metrics that are cheap enough to collect continuously. Retention and administration run
rarely and can run outside busy periods, so they can absorb slower queries.

The processing loop only ever looks for incomplete records, while the retained history can be
far larger. Indexing only incomplete records keeps the hot path independent of that history.

JDBC users work directly with SQL, so per-dialect tuning can reach them without changing any
contract. JPA users choose a provider abstraction, and portable JPQL keeps that abstraction
intact.

## Consequences

- Processing and metrics queries get faster.
- Priority 3 queries become slower. This is accepted.
- The `COMPLETED` gauge disappears by default, which dashboards and alerts that use it will
  notice. Release notes must call this out.
- `completed_at IS NULL` must hold exactly for `NEW` and `FAILED` records. Queries for
  `COMPLETED` must not add that condition, or they silently count nothing.
- JDBC has more query classes to maintain and test, and every dialect must return the same
  results.
- Existing installations keep their old indexes until they migrate, because schema
  initialization uses `IF NOT EXISTS`. Upgrade steps must describe dropping and creating them.

## Follow-up Work

- Benchmark the indexes and queries on MySQL, MariaDB, SQL Server, Oracle, and H2.
- Apply the same priorities to MongoDB, which this decision does not change.
- Evaluate UUIDv7 for record IDs as a separate decision.
