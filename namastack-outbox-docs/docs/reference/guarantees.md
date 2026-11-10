---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Reliability Guarantees
description: What Namastack Outbox guarantees about message delivery, ordering, failure recovery, and scalability — and what it intentionally does not guarantee.
sidebar_position: 13
---

# Reliability Guarantees

Understanding what Namastack Outbox guarantees — and what it does not — is essential for
designing robust, event-driven systems. These guarantees are enforced at the library level and
apply regardless of the underlying database or messaging broker you use.

## What is Guaranteed

### At-Least-Once Delivery

Every outbox record will be delivered to its handler at least once. Records are never silently
dropped. If a handler throws an exception or the application crashes mid-processing, the record
remains in the database and will be retried on the next polling cycle.

This guarantee holds across application restarts, JVM crashes, and network failures — because
the outbox record is always persisted to the same database transaction as your business data
before any delivery attempt begins.

### Ordering Per Key

Records with the same key are processed sequentially, ordered by `createdAt` and then by `id`.
With `stop-on-first-failure=true` (the default), a failed record or a pending retry prevents
later records with that key from being processed. A successful fallback with disposition
`COMPLETE` completes the failed record and unblocks the key.

With [Processing Groups](processing-groups.md) enabled, the ordering boundary is
`(processing group, business key)` within one outbox. Every matching handler receives its own
delivery; deliveries of handlers in the same group are processed sequentially. Different keys
within a partition are processed concurrently, and all groups share worker and database capacity.

The order follows record timestamps and IDs, not transaction commit order or business-event
causality. Records from one scheduling call share a timestamp. A concurrent producer can commit
a record with an earlier timestamp after another record has already been processed. With
`stop-on-first-failure=false`, later records are processed even when earlier ones failed or are
waiting for a retry.

### Failure Recovery

A system failure (JVM crash, OOM, power loss) at any point during processing does not result in
lost records. A record remains `NEW` in the database until processing completes successfully or
fails permanently. On restart, the library picks up where it left off and resumes processing.

If an instance crashes while processing a record, stale-instance detection makes its partition
available to an active instance after a configurable timeout. The `NEW` record can then be
processed again.

### Horizontal Scalability

Multiple application instances share work through database-backed partition coordination.
Partition ownership determines which instance processes a key during normal operation. After
failures or ownership changes a record can be processed more than once, so delivery is
at least once. Throughput depends on available workers, independent keys, and database capacity.

### Transactional Consistency

Outbox records are written inside the same database transaction as your business data. Either
both the business operation and the outbox record are committed, or neither is. This eliminates
the dual-write problem that affects systems relying on direct message broker writes alongside
database operations.

### Automatic Retry

Failed records (handlers that throw exceptions) are automatically retried according to
configurable backoff policies. See [Retry Mechanisms](retry.md) for the full retry configuration
reference.

### Automatic Rebalancing

When instances join or leave the cluster, partitions are automatically redistributed. No manual
rebalancing step is required. Coordination runs at the configured rebalance interval. Recovery
from an unresponsive instance also depends on heartbeat expiration and stale-instance cleanup.

### Throughput Limits

Additional workers can increase throughput when independent keys are available. Scaling is
limited by database capacity, executor capacity, partition distribution, and slow handlers.
Processing groups separate ordering boundaries but do not reserve resources or guarantee linear
throughput growth.

---

## What is NOT Guaranteed

### Exactly-Once Delivery

Records may be processed **more than once**. This happens in scenarios such as:

- The handler succeeds but the application crashes before the outbox record is marked as
  `COMPLETED`
- A record is picked up by two instances during a rebalancing window

**Consequence:** Handlers must be **idempotent** — processing the same record twice must produce
the same outcome as processing it once. Common strategies include checking for existing results
before acting, using database unique constraints on the output side, or using the record's ID as
an idempotency key in downstream systems.

### Global Ordering

There is no guaranteed ordering across records with different keys. Two records with different
keys may be processed in any order relative to each other, even if one was inserted before the
other.

Using a single key (and a single processing group) for all records processes them
sequentially, at the cost of parallelism. The order is still based on record timestamps, not on
commit order or business-event causality. Different processing groups have no ordering
relationship, even for the same business key.

### Real-Time Processing

Records are processed **asynchronously** on polling intervals — not immediately when inserted.
By default, the polling interval is 2 seconds. Under load, processing may be delayed further
if the processing queue is full.

Namastack Outbox is designed for reliable, durable delivery — not for low-latency, synchronous
messaging. If sub-second delivery is required, consider pairing the outbox with an adaptive
polling strategy (see [Polling Strategies](polling.md)) or reducing the polling interval.

---

:::tip Next Steps
Ready to get started? Check out the [Quick Start Guide](../quickstart.mdx) to integrate Namastack
Outbox for Spring Boot into your application.
:::
