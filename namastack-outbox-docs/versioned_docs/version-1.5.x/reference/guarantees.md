---
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

Records with the same key are always processed in the order they were inserted. If two records
share the same key, the second will never be processed before the first has completed
successfully.

This is enforced via partition-based processing: each key is consistently assigned to a single
processing partition, and partitions are processed sequentially within a single instance. Across
multiple application instances, the same partition — and therefore the same key — is only
processed by one instance at a time.

### Failure Recovery

A system failure (JVM crash, OOM, power loss) at any point during processing does not result in
lost records. Records remain `PENDING` or `PROCESSING` in the database. On restart, the library
picks up where it left off and resumes processing.

Records that were mid-processing when a crash occurred are automatically detected as stale and
returned to the `PENDING` state after a configurable timeout.

### Horizontal Scalability

Multiple application instances can process outbox records concurrently without coordination
overhead or duplicate processing. The library uses database-level locking to distribute
partitions across instances. Adding more instances linearly increases throughput.

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
rebalancing step or coordination is required. Rebalancing is triggered on the next polling cycle
and completes without downtime.

### Linear Throughput Scaling

Throughput scales proportionally with the number of application instances, up to the limit of
database I/O capacity. Adding a second instance roughly doubles throughput; adding a third
roughly triples it, and so on.

---

## What is NOT Guaranteed

### Exactly-Once Delivery

Records may be processed **more than once**. This happens in scenarios such as:

- The handler succeeds but the application crashes before the outbox record is marked as
  `PROCESSED`
- A record is picked up by two instances during a rebalancing window

**Consequence:** Handlers must be **idempotent** — processing the same record twice must produce
the same outcome as processing it once. Common strategies include checking for existing results
before acting, using database unique constraints on the output side, or using the record's ID as
an idempotency key in downstream systems.

### Global Ordering

There is no guaranteed ordering across records with different keys. Two records with different
keys may be processed in any order relative to each other, even if one was inserted before the
other.

If global ordering matters for your use case, you must either use a single key for all records
(which eliminates horizontal parallelism) or handle ordering at the consumer side.

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
