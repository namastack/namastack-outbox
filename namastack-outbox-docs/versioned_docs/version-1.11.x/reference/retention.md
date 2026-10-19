---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Record Retention
description: Delete completed outbox records right after processing, or delete completed and failed records after a retention period.
sidebar_position: 12.5
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Record Retention

By default, terminal records (`COMPLETED` and `FAILED`) stay in the outbox forever. Keeping them
supports auditing, debugging, and manual replay, but the table or collection grows without bound.
Namastack Outbox offers two ways to limit that:

- [Delete completed records immediately](#delete-completed-records-immediately) after successful
  processing, via configuration.
- [Delete records after a retention period](#delete-records-after-a-retention-period) by calling
  repository methods from your own scheduled job.

`NEW` records are never deleted by either option.

## Delete Completed Records Immediately

| Property                                               | Default | Description                                                                   |
|--------------------------------------------------------|---------|-------------------------------------------------------------------------------|
| `namastack.outbox.processing.delete-completed-records` | `false` | Delete a record after successful processing instead of marking it `COMPLETED` |

```yaml
namastack:
  outbox:
    processing:
      delete-completed-records: true
```

When enabled, a record is deleted as soon as its handler succeeds, or as soon as its fallback
handler succeeds with the default disposition. It is never stored as `COMPLETED`, so no history of
successfully processed records is kept.

To keep completed records for a while instead, leave this option disabled and use time-based
retention. Time-based retention of `FAILED` records works with either setting.

## Delete Records After a Retention Period

`OutboxRecordRepository` provides bounded methods to delete terminal records older than a cutoff.
The library does not schedule them: call them from your own job (`@Scheduled`, ShedLock, Quartz,
a Kubernetes CronJob, ...).

### Methods

| Method                                           | Description                                                                                    |
|--------------------------------------------------|------------------------------------------------------------------------------------------------|
| `deleteCompletedRecords(completedBefore, limit)` | Deletes at most `limit` `COMPLETED` records with `completedAt < completedBefore`, oldest first |
| `deleteFailedRecords(lastRetryBefore, limit)`    | Deletes at most `limit` `FAILED` records with `nextRetryAt < lastRetryBefore`, oldest first    |

Both methods return the number of records actually deleted. To delete everything that is eligible,
call them repeatedly until they return less than `limit`.

### Example

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OutboxRetentionJob(
    private val recordRepository: OutboxRecordRepository,
    private val clock: Clock,
) {
    @Scheduled(cron = "0 0 3 * * *")
    fun cleanup() {
        val now = Instant.now(clock)
        deleteInBatches { recordRepository.deleteCompletedRecords(now.minus(Duration.ofDays(7)), BATCH_SIZE) }
        deleteInBatches { recordRepository.deleteFailedRecords(now.minus(Duration.ofDays(30)), BATCH_SIZE) }
    }

    private fun deleteInBatches(deleteBatch: () -> Int) {
        while (deleteBatch() == BATCH_SIZE) {
            // continue until a partial batch has been deleted
        }
    }

    private companion object {
        const val BATCH_SIZE = 1000
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OutboxRetentionJob {

    private static final int BATCH_SIZE = 1000;

    private final OutboxRecordRepository recordRepository;
    private final Clock clock;

    public OutboxRetentionJob(OutboxRecordRepository recordRepository, Clock clock) {
        this.recordRepository = recordRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        Instant now = Instant.now(clock);
        Instant completedBefore = now.minus(Duration.ofDays(7));
        Instant failedBefore = now.minus(Duration.ofDays(30));

        while (recordRepository.deleteCompletedRecords(completedBefore, BATCH_SIZE) == BATCH_SIZE) {
            // continue until a partial batch has been deleted
        }
        while (recordRepository.deleteFailedRecords(failedBefore, BATCH_SIZE) == BATCH_SIZE) {
            // continue until a partial batch has been deleted
        }
    }
}
```

</TabItem>
</Tabs>

### Inspecting Records Before Deletion

To export or archive records before they are deleted, read them with
`findCompletedRecords(completedBefore, limit)` or `findFailedRecords(lastRetryBefore, limit)`. They
return the same records, in the same order, as the corresponding delete methods. Then remove them
with `deleteByIds(ids)`:

```kotlin
val records = recordRepository.findCompletedRecords(completedBefore, BATCH_SIZE)
archive(records)
recordRepository.deleteByIds(records.map { it.id })
```

`deleteByIds` deletes the given records regardless of their status. The find methods deserialize
payloads, so they fail for records whose payload class no longer exists. The delete methods do not
deserialize records and remove such records as well.

## Considerations

### Age of Failed Records

The age of a `FAILED` record is measured by `nextRetryAt`, the scheduled time of its last attempt.
It is a lower bound for the time the record failed, not an exact failure timestamp: a record
created at 10:00 and first processed and permanently failed at 18:00 still has `nextRetryAt` 10:00.
Choose a failed-record retention that is clearly longer than the time your records may wait before
being processed.

### Record-Key Ordering

With `stop-on-first-failure` enabled, a `FAILED` record blocks later records of the same record
key. Deleting it releases that barrier, and the later records are processed afterwards.

### Multiple Instances

The methods are not scoped to partitions. Running the job on every instance is safe but does
redundant work; use a scheduler lock (for example ShedLock) to run it on one instance at a time.

### Batch Size

The delete methods select the eligible ids and delete them with `deleteByIds` in a single
statement; the library does not split them. Some databases limit the size of an `IN` list
(Oracle: 1000 expressions, SQL Server: 2100 parameters), so keep `limit` (and the collection
passed to `deleteByIds`) within that limit.

### Atomicity

A batch is not guaranteed to be deleted atomically on every persistence module (MongoDB selects
the ids and deletes them in a separate operation). A record that changes between selecting and
deleting its id is still deleted.

## Indexes

`FAILED` retention uses the existing `(status, next_retry_at)` index. For large tables, `COMPLETED`
retention benefits from an additional index, which is not created automatically:

<Tabs>
<TabItem value="sql" label="Relational databases">

```sql
CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);
```

</TabItem>
<TabItem value="mongodb" label="MongoDB">

```javascript
db.outbox_records.createIndex({ status: 1, completedAt: 1 }, { name: "status_completed_idx" })
```

</TabItem>
</Tabs>
