---
title: Virtual Threads Support
description: How Namastack Outbox integrates with Java virtual threads in Spring Boot for improved scalability, and when to use virtual threads versus platform thread pools.
sidebar_position: 11
---

# Virtual Threads Support

When virtual threads are enabled in your Spring Boot application, Namastack Outbox automatically
detects the configuration and switches its processing executor to use virtual threads. No code
changes or additional configuration are required.

## Enabling Virtual Threads

Add the following to your `application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Spring Boot 3.2+ automatically configures a virtual-thread-based executor when this property is
set. Namastack Outbox detects the presence of this executor and uses it for outbox processing.

---

## Benefits for Outbox Processing

Outbox processing is an I/O-bound workload: each polling cycle queries the database, and each
handler typically writes to another database, calls an HTTP API, or publishes to a message
broker. These are exactly the scenarios where virtual threads excel.

**Higher Concurrency with Less Memory**

Platform threads are relatively expensive — each requires a dedicated OS thread with a 512KB–1MB
stack by default. Virtual threads are cheap: the JVM can maintain millions of them with minimal
heap overhead. For outbox processing, this means you can handle a much larger number of
concurrent in-flight records without tuning thread pool sizes.

**Simpler Configuration**

With platform threads, you must carefully balance `executor-core-pool-size` and
`executor-max-pool-size` based on expected concurrency. With virtual threads, you simply set a
concurrency limit (or leave it unlimited) and let the JVM manage the rest.

**Better I/O Utilization**

When a virtual thread blocks on a database call or HTTP request, the underlying carrier thread
is released and can execute other virtual threads. This eliminates the head-of-line blocking
that occurs with platform thread pools when all threads are waiting on slow I/O.

---

## Configuration

When virtual threads are active, use `executor-concurrency-limit` instead of pool size
properties:

```yaml
namastack:
  outbox:
    processing:
      executor-concurrency-limit: -1    # -1 = unlimited; or set a specific cap
```

Setting `executor-concurrency-limit` to a positive integer limits the maximum number of
concurrently executing handlers. This is useful when downstream systems (e.g., a rate-limited
HTTP API or a database with a small connection pool) cannot handle unbounded concurrency.

---

## Platform Threads (Default)

When virtual threads are not enabled, Namastack Outbox uses a standard platform thread pool:

```yaml
namastack:
  outbox:
    processing:
      executor-core-pool-size: 4
      executor-max-pool-size: 8
```

The platform thread pool is appropriate when:

- You are running on Java 17 or earlier (virtual threads require Java 21+)
- Your handlers are CPU-bound rather than I/O-bound
- You need precise control over OS thread resource usage

---

## When NOT to Use Virtual Threads

Virtual threads are not a universal improvement. Avoid them (or use `executor-concurrency-limit`
to cap concurrency) in these scenarios:

**Handler holds a database connection for a long duration**
If your handler acquires a connection from a pool and then does significant non-database work
before releasing it, high virtual-thread concurrency can exhaust the connection pool. Cap
concurrency to match your pool size.

**Downstream system has a strict rate limit**
If your handler calls an external API with a rate limit (e.g., 100 requests/second), unlimited
virtual-thread concurrency will trigger rate-limit errors. Set `executor-concurrency-limit`
accordingly.

**Handler uses thread-local state that is not virtual-thread-safe**
Some libraries use `ThreadLocal` for state that assumes one-to-one mapping with OS threads.
These may behave unexpectedly with virtual threads. Review your handler dependencies before
enabling virtual threads.

---

:::tip Java Version Requirement
Virtual threads require **Java 21 or later** and **Spring Boot 3.2 or later**. On earlier
versions, `spring.threads.virtual.enabled` is ignored and platform thread pools are used.
:::
