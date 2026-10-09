# ADR-0003: Retention of Terminal Records

- **Status:** Accepted
- **Date:** 2026-10-09
- **Decision owner:** @Alek96
- **Discussion:** https://github.com/orgs/namastack/discussions/486
- **Related:** None
- **Supersedes:** None

## Context

Namastack Outbox offers a single retention control for processed records:
`namastack.outbox.processing.delete-completed-records` either keeps every `COMPLETED`
record forever (default) or deletes it immediately after successful completion. There is
no retention control for `FAILED` records at all.

In long-running systems the outbox grows without bound, which increases storage and index
size, degrades polling queries, and forces every application to write its own cleanup SQL or
MongoDB queries. At the same time, many applications want to keep terminal records for a
limited period for auditing, debugging, incident analysis, or manual replay.

## Requirements and Constraints

- Records in `NEW` status are never deleted by retention.
- `COMPLETED` and `FAILED` retention can be invoked independently.
- Retention is disabled by default; current behavior is preserved.
- Large deletions are bounded so that they do not produce long-running transactions.
- JDBC, JPA, and MongoDB provide equivalent behavior.
- `delete-completed-records` keeps its meaning.
- No database schema change.
- Non-goal: a built-in cleanup scheduler, retention configuration properties, or support for
  the Channels / multi-runtime mode in this iteration.

## Considered Options

### Option A: Repository methods

Extend `OutboxRecordRepository` with methods that delete terminal records older than a
cutoff. The library does not schedule them; applications call them from their own jobs
(`@Scheduled`, ShedLock, Quartz, Kubernetes CronJob, ...).

### Option B: Configuration-driven cleanup

Add retention properties and a built-in background task that periodically deletes eligible
records in the partitions owned by each instance. It works without application code, but it
introduces another lifecycle component that competes with processing for database resources,
must handle rebalancing, and is less flexible regarding maintenance windows.

### Option C: Both

Expose the repository methods publicly and build the optional cleanup task from Option B on
top of them.

### Partition-scoped methods

A variant of Option A considered passing the owned partitions to every retention method so
that instances do not delete the same rows concurrently. This would couple a persistence
capability to the distributed processing model.

## Decision

Option A without partition arguments. `OutboxRecordRepository` gains the following abstract
methods:

```kotlin
fun findCompletedRecords(completedBefore: Instant, limit: Int): List<OutboxRecord<*>>
fun findFailedRecords(lastRetryBefore: Instant, limit: Int): List<OutboxRecord<*>>
fun deleteByIds(ids: Collection<String>): Int
fun deleteCompletedRecords(completedBefore: Instant, limit: Int): Int
fun deleteFailedRecords(lastRetryBefore: Instant, limit: Int): Int
```

- `COMPLETED` age is measured by `completedAt`.
- `FAILED` age is measured by `nextRetryAt`. When a record is marked `FAILED`, `nextRetryAt`
  keeps the scheduled time of the last attempt, so it is a lower bound for the failure time
  rather than an exact failure timestamp. The parameter name `lastRetryBefore` makes that
  explicit.
- The find methods overload the existing `findCompletedRecords()` / `findFailedRecords()` and
  return at most `limit` records, oldest first. A non-positive `limit` is rejected.
- `deleteByIds` deletes the given ids regardless of status with a single statement, so
  applications can inspect, export, or archive records before deleting them.
- The bounded delete methods select at most `limit` eligible ids (without deserializing the
  records) and delete them with `deleteByIds`. They return the number of records actually
  deleted.
- Scheduling, retention periods, partition scoping, and cross-instance coordination are the
  application's responsibility.

## Rationale

Retention operates on terminal records, which do not need the partition ownership model that
guarantees correct processing of `NEW` records. Keeping the repository API focused on a small
bounded persistence capability lets applications use whatever scheduling and coordination
infrastructure they already have, and keeps a future built-in cleanup (Option B) possible as
a thin layer on top. The find and delete-by-ids methods are exposed because they enable
archiving workflows, and a limited delete has to be built from "select ids, delete by ids" in
JPA and MongoDB anyway (neither supports a limited bulk delete).

`nextRetryAt` avoids deleting a record that retried for days right after it became `FAILED`,
which would happen with `createdAt`, and it is covered by the existing `(status, next_retry_at)`
indexes. A dedicated failure timestamp would require a schema change.

## Consequences

- Custom `OutboxRecordRepository` implementations must implement the new methods.
- Deleting a `FAILED` record releases its record-key barrier when `stop-on-first-failure` is
  enabled; later records of that key are then processed. This is documented behavior.
- Deletion of a batch is not guaranteed to be atomic across persistence implementations
  (MongoDB selects ids first and deletes them in a separate operation). A record that changes
  between selecting and deleting its id is still deleted, for example a `FAILED` record that is
  reset to `NEW` in the meantime.
- The library does not split large deletions. Some databases limit the size of an `IN` list
  (Oracle: 1000 expressions, SQL Server: 2100 parameters); callers choose `limit` and the size of
  the `ids` collection accordingly.
- The bounded delete methods do not deserialize records, so records whose payload type no longer
  exists on the classpath can still be deleted.
- Concurrent cleanup from several instances is safe but may do redundant work; applications
  that run cleanup on every instance should coordinate it (for example with ShedLock).
- `FAILED` retention is covered by the existing `(status, next_retry_at)` index. `COMPLETED`
  retention only has a `status` index; an additional `(status, completed_at)` index is
  documented as an optional performance recommendation and is not created automatically.
- `delete-completed-records` is unchanged.

## Follow-up Work

- Optional configuration-driven cleanup task built on these methods.
- Dedicated `failedAt` timestamp.
- Retention metrics through the instrumentation SPI (ADR-0002).
- Channels / multi-runtime support.
