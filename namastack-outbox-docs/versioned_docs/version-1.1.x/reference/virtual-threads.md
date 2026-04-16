---
title: Virtual Threads Support
description: Automatic virtual threads integration for better scalability.
sidebar_position: 11
---

# Virtual Threads Support

When virtual threads are enabled in Spring Boot, Namastack Outbox automatically uses virtual threads for outbox processing, providing better scalability for I/O-bound workloads.

## Enabling Virtual Threads

Enable virtual threads in your Spring Boot application:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

**Benefits:**

- **Higher Throughput**: Handle more concurrent outbox records with fewer resources
- **Lower Memory**: Virtual threads have minimal memory overhead
- **Better I/O Handling**: Ideal for handlers that make external API calls
- **Automatic**: No code changes required - the library detects and uses virtual threads automatically

---

## Configuration

When virtual threads are enabled, use the concurrency limit instead of pool sizes:

```yaml
namastack:
  outbox:
    processing:
      executor-concurrency-limit: -1           # -1 for unlimited, or set a specific limit
```

:::note

**Platform Threads**

When virtual threads are disabled (default), the library uses traditional thread pools:

```yaml
namastack:
  outbox:
    processing:
      executor-core-pool-size: 4
      executor-max-pool-size: 8
```
