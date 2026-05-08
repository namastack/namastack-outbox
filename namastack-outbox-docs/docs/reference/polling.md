---
title: Polling Strategies
description: Learn about fixed and adaptive polling strategies for outbox processing, including configuration options, use cases, and how to choose the right strategy for your workload.
sidebar_position: 4
---

# Polling Strategies

Namastack Outbox uses a polling loop to detect and process pending outbox records. The library
supports two polling strategies — **Fixed** and **Adaptive** — which differ in how the interval
between poll cycles is managed. Choosing the right strategy depends on the predictability and
variability of your outbox write workload.

---

## Fixed Polling

Fixed polling uses a constant interval between poll cycles, regardless of whether the previous
cycle found work to do.

**How it works:**

The polling loop wakes up every `interval` milliseconds, queries the database for pending
records, and processes up to `batch-size` record keys. It then sleeps for the configured
interval before repeating — whether the batch was empty or full.

**Best for:**

- Workloads with predictable, steady throughput (e.g., scheduled jobs, regular event streams)
- Systems where latency consistency is more important than minimizing database queries
- Development and testing environments where simplicity is preferred

**Trade-off:** During idle periods, Fixed polling issues the same number of database queries as
during peak load. For workloads with significant idle time (nights, weekends), this generates
unnecessary database traffic.

**Configuration:**

| Property                                  | Default | Description                                                                  |
|-------------------------------------------|---------|------------------------------------------------------------------------------|
| `namastack.outbox.polling.trigger`        | `fixed` | Selects the polling strategy                                                 |
| `namastack.outbox.polling.fixed.interval` | `2s`    | Interval between poll cycles. Supports duration strings: `2s`, `500ms`, `1m` |
| `namastack.outbox.polling.batch-size`     | `10`    | Maximum number of record keys to process per poll cycle                      |

**Example configuration:**

```yaml
namastack:
  outbox:
    polling:
      trigger: fixed
      batch-size: 20
      fixed:
        interval: 1s
```

---

## Adaptive Polling

Adaptive polling dynamically adjusts the interval between poll cycles based on observed
workload. When the system is busy, it polls more frequently. When it is idle, it backs off to
reduce unnecessary database load.

**How it works:**

After each poll cycle, the adaptive strategy evaluates how full the batch was:

- If **fewer than 25%** of `batch-size` keys were processed (low load), the interval doubles
  (up to `max-interval`)
- If a **full batch** was processed (high load), the interval halves (down to `min-interval`)
- If load is **between 25% and 100%** of batch size, the interval stays the same

This means the system converges quickly on the minimum interval during bursts, and gradually
backs off to the maximum interval during quiet periods.

**Best for:**

- Workloads with variable throughput (e.g., user-triggered events, mixed traffic patterns)
- Production systems where database query cost matters
- Systems with significant idle periods between activity bursts

**Trade-off:** During sudden traffic spikes, adaptive polling may take a few cycles to converge
on the minimum interval, adding a small amount of initial latency compared to Fixed polling.

**Configuration:**

| Property                                         | Default    | Description                                                      |
|--------------------------------------------------|------------|------------------------------------------------------------------|
| `namastack.outbox.polling.trigger`               | `adaptive` | Selects the polling strategy                                     |
| `namastack.outbox.polling.adaptive.min-interval` | `1s`       | Minimum interval between polling cycles (floor during high load) |
| `namastack.outbox.polling.adaptive.max-interval` | `8s`       | Maximum interval between polling cycles (ceiling during idle)    |
| `namastack.outbox.polling.batch-size`            | `10`       | Maximum number of record keys to process per poll cycle          |

**Example configuration:**

```yaml
namastack:
  outbox:
    polling:
      trigger: adaptive
      batch-size: 20
      adaptive:
        min-interval: 500ms
        max-interval: 30s
```

---

## Choosing a Strategy

| Criterion                          | Fixed             | Adaptive            |
|------------------------------------|-------------------|---------------------|
| Workload is steady and predictable | ✅ Preferred       | Works               |
| Workload is bursty or idle-heavy   | Works             | ✅ Preferred         |
| Minimizing database queries        | —                 | ✅ Better            |
| Minimizing processing latency      | ✅ More consistent | Adds warmup latency |
| Simplicity of configuration        | ✅ One parameter   | Two parameters      |

As a general rule: use **Adaptive** for production deployments and **Fixed** for local
development or when your SLA requires a guaranteed maximum processing delay.

:::tip Related Configuration
The `batch-size` property affects how quickly the adaptive strategy reacts to load changes.
A larger batch size means the strategy is less sensitive to small bursts. See
[Processing Chain](processing.md) for how records are processed within each batch.
:::
