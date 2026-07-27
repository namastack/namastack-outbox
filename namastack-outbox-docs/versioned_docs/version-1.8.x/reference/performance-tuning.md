---
title: Performance Tuning
sidebar_position: 6
---

# Performance Tuning

Namastack Outbox throughput is primarily controlled by four dimensions:

- How many application instances process records
- How many record keys each polling cycle loads
- How often each instance polls
- How much concurrent database and handler work each instance may start

This page summarizes the current performance-test results and gives practical starting points for
production tuning.

:::info Benchmark scope
The numbers below are local benchmark results from the standalone performance-test module. They are
useful for comparing settings, but they are not a universal throughput guarantee. Database hardware,
handler work, payload size, indexes, connection pool limits, and other application traffic can change
capacity significantly.
:::

:::warning Repeat candidate profiles
The latest rerun did not reproduce every earlier observation. In particular, the previous best
6-instance, 25ms, batch-800 profile failed in the fresh pass, while virtual threads and 100ms polling
passed. Treat a single local run as directional. Before adopting a profile, repeat it several times
on a quiet machine and keep the setting that remains healthy across repeated runs.
:::

## Test Environment

The latest tuning pass was run on:

| Component | Value |
| --- | --- |
| Machine | Apple MacBook Pro 16 inch |
| CPU | Apple M2 Pro |
| Memory | 32 GB |
| Java | 21.0.2 |
| OS | macOS 26.5.1 on arm64 |
| Docker server | 29.2.0 |
| Database | PostgreSQL 17.10 in Docker Compose |
| Workload | Payment event benchmark, JDBC outbox, empty handler |
| Record ordering | 10 records per key |
| Producer batch | 100 records per producer transaction |
| Duration | 2 minutes steady-state production |
| Report prefix | `docs-refresh-20260604T182233Z` |

The benchmark used the performance-test module and measured whether consumers could keep up while a
producer inserted records at a fixed target rate. Capacity was considered sustainable only when the
producer target was met, stabilized backlog growth was at most `100 records/s`, and the end backlog
was at most `20000` records.

## Baseline Command

The baseline steady-state command shape was:

```bash
./run-performance-test.sh \
  --mode=steady-state \
  --profile=steady-state-payments-poll-25ms \
  --instances=4 \
  --records-per-key=10 \
  --warmup-records=25000 \
  --batch-size=800 \
  --poll-interval=25ms \
  --producer-rate=10000 \
  --producer-duration=2m \
  --producer-batch-size=100 \
  --producer-workers=4 \
  --measurement-warmup=30s \
  --run-timeout=10m \
  --sample-interval=5s \
  --skip-build=true
```

## Best Result in the Latest Pass

The best result in the latest pass was the 4-instance virtual-thread profile with an explicit
concurrency limit of `30` per instance:

```bash
PERF_VIRTUAL_THREADS=true \
NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT=30 \
./run-performance-test.sh \
  --mode=steady-state \
  --profile=steady-state-payments-poll-25ms-vthreads-limit30 \
  --instances=4 \
  --records-per-key=10 \
  --warmup-records=25000 \
  --batch-size=800 \
  --poll-interval=25ms \
  --producer-rate=10000 \
  --producer-duration=2m \
  --producer-batch-size=100 \
  --producer-workers=4 \
  --measurement-warmup=30s \
  --run-timeout=10m \
  --sample-interval=5s \
  --skip-build=true
```

Result on the test machine:

| Metric | Result |
| --- | ---: |
| Run ID | `docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30` |
| Target producer rate | 10000 records/s |
| Actual producer rate | 9997.93 records/s |
| Processing rate during production | 9981.63 records/s |
| Stabilized backlog growth | 0.34 records/s |
| Backlog at producer stop | 1956 records |
| Drain duration after producer stop | 0.18 s |
| Processing latency p50 / p95 / p99 | 0.08 / 0.25 / 0.35 s |
| Capacity assessment | Sustainable |

### Report Graphs for the Best Run

These charts are copied from the generated performance report for
`docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30`.

![Produced vs processed throughput, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/producer-vs-consumer.svg)

![Total processing throughput, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/throughput-total.svg)

![Processing throughput by consumer, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/throughput-by-consumer.svg)

![Backlog, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/backlog.svg)

![Consumer CPU, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/consumer-cpu.svg)

![Consumer memory, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/consumer-memory.svg)

![PostgreSQL metrics, 10000 records per second with virtual threads](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-vthreads-limit30/postgres.svg)

## What the Latest Tests Showed

| Scenario | Result | Processing rate | Backlog growth | Stop backlog | Drain | p99 latency |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 5000 records/s, 4 instances, 25ms, batch 800 | Sustainable | 4997.67/s | 1.02/s | 300 | 0.06 s | 0.19 s |
| 7500 records/s, 4 instances, 25ms, batch 800 | Sustainable | 7496.27/s | 19.92/s | 367 | 0.07 s | 0.67 s |
| 10000 records/s, 4 instances, 25ms, batch 800 | Unsustainable | 5935.33/s | 5696.72/s | 487200 | 74.02 s | 76.86 s |
| 10000 records/s, 6 instances, 25ms, batch 800 | Unsustainable | 6076.64/s | 5309.74/s | 469633 | 103.65 s | 111.18 s |
| 10000 records/s, 4 instances, 100ms, batch 800 | Sustainable | 9938.85/s | -34.17/s | 7270 | 0.71 s | 1.71 s |
| 10000 records/s, 6 instances, 25ms, batch 500 | Unsustainable | 8872.68/s | 1016.31/s | 133642 | 22.71 s | 24.05 s |
| 10000 records/s, 6 instances, 25ms, batch 1000 | Sustainable | 9947.87/s | 14.71/s | 6038 | 0.60 s | 0.70 s |
| 10000 records/s, 4 instances, virtual threads, limit 30 | Best latest run | 9981.63/s | 0.34/s | 1956 | 0.18 s | 0.35 s |
| 10000 records/s, 4 instances, queue capacity 0, pool 30 | Unsustainable | 3607.24/s | 8949.48/s | 766190 | 439.94 s | 440.41 s |

Key observations from this pass:

- 5000 and 7500 records/s were healthy with the original 4-instance, 25ms, batch-800 profile.
- 10000 records/s was possible, but not with every plausible setting.
- The 4-instance, 25ms, batch-800 baseline became unstable at 10000 records/s and showed heartbeat re-registration warnings.
- Adding instances alone did not fix the unstable 10000 records/s profile in this pass; 6 instances with batch 800 also showed heartbeat re-registration warnings.
- The successful 10000 records/s platform-thread profiles were less aggressive: either 100ms polling with batch 800, or 6 instances with batch 1000.
- The best single result used virtual threads with a bounded concurrency limit of 30.
- `queue-capacity=0` remained clearly unsafe with large batches because scheduler task submission was rejected.

## Baseline Report Graphs

The baseline runs show where the original 4-instance setup starts to lose headroom.

### 5000 Records per Second, 4 Instances

![Produced vs processed throughput, 5000 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-5000/producer-vs-consumer.svg)

![Backlog, 5000 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-5000/backlog.svg)

### 7500 Records per Second, 4 Instances

![Produced vs processed throughput, 7500 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-7500/producer-vs-consumer.svg)

![Backlog, 7500 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-7500/backlog.svg)

### 10000 Records per Second, 4 Instances

![Produced vs processed throughput, 10000 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-10000/producer-vs-consumer.svg)

![Backlog, 10000 records per second baseline](/img/performance-tuning/docs-refresh-20260604T182233Z-baseline-10000/backlog.svg)

## Tuning Comparison Graphs

These report graphs show the main tuning alternatives from the latest pass.

### 10000 Records per Second, 100ms Polling

![Produced vs processed throughput, 100ms polling](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-poll100/producer-vs-consumer.svg)

![Backlog, 100ms polling](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-poll100/backlog.svg)

### 10000 Records per Second, 6 Instances, Batch 800

![Produced vs processed throughput, 6 instances batch size 800](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances/producer-vs-consumer.svg)

![Backlog, 6 instances batch size 800](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances/backlog.svg)

### 10000 Records per Second, 6 Instances, Batch 500

![Produced vs processed throughput, batch size 500](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances-batch500/producer-vs-consumer.svg)

![Backlog, batch size 500](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances-batch500/backlog.svg)

### 10000 Records per Second, 6 Instances, Batch 1000

![Produced vs processed throughput, batch size 1000](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances-batch1000/producer-vs-consumer.svg)

![Backlog, batch size 1000](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-6instances-batch1000/backlog.svg)

### 10000 Records per Second, Queue Capacity 0

![Produced vs processed throughput, queue capacity 0](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-exec30-q0/producer-vs-consumer.svg)

![Backlog, queue capacity 0](/img/performance-tuning/docs-refresh-20260604T182233Z-tuned-10000-4inst-exec30-q0/backlog.svg)

## Tuning Guidelines

### Start with a Measured Baseline

First run the workload close to the real production shape:

- Same database class and storage type
- Same record-key distribution
- Same handler latency profile
- Same producer transaction batch size
- Same instance count or pod CPU limits

For this local payment benchmark, the 4-instance baseline was healthy up to 7500 records/s but not
at 10000 records/s in the latest pass.

### Do Not Assume More Instances Fix Every Bottleneck

Adding consumers distributes the 256 partitions across more JVMs, but it also adds database
connections, heartbeat writes, partition coordination work, and scheduler activity. In the latest
pass, 6 instances with 25ms polling and batch 800 was worse than the 4-instance 100ms profile.

Use more instances when:

- Each consumer has high CPU usage or high handler latency
- PostgreSQL has connection and write capacity available
- Heartbeat and rebalance logs stay quiet under load

Do not keep scaling instances if heartbeat re-registration warnings appear during the run.

### Tune Poll Interval and Batch Size Together

`batch-size` is the number of record keys loaded per polling cycle. It is not the number of records,
because multiple records can share the same key and must be processed in order.

The scheduler loads up to `batch-size` keys, submits one task per key, and waits for all submitted
key tasks before the cycle completes. That makes the setting sensitive:

- Too small can increase cycle overhead and reduce effective throughput.
- Too aggressive polling can add scheduler and query churn.
- Too large can create long cycles if the executor or database cannot keep up.

In the latest pass, two platform-thread settings reached 10000 records/s:

```yaml
namastack:
  outbox:
    polling:
      trigger: fixed
      batch-size: 800
      fixed:
        interval: 100ms
```

```yaml
namastack:
  outbox:
    polling:
      trigger: fixed
      batch-size: 1000
      fixed:
        interval: 25ms
```

The first option used 4 instances and had p99 latency of 1.71s. The second option used 6 instances
and had p99 latency of 0.70s.

### Use Virtual Threads as an Explicit Test Profile

Virtual threads were the best result in the latest pass, but they should be tested deliberately.
They are most useful when handlers block on I/O, such as HTTP APIs or messaging clients. This
benchmark uses a fast empty handler, so the result mainly shows that bounded virtual-thread
scheduling can reduce platform-thread executor pressure in this environment.

Always set a concurrency limit that matches downstream capacity:

```yaml
spring:
  threads:
    virtual:
      enabled: true

namastack:
  outbox:
    processing:
      executor-concurrency-limit: 30
```

Do not leave virtual-thread concurrency unlimited unless every downstream dependency has been sized
for that load.

### Do Not Use Zero Queue Capacity with Large Batches

A platform-thread run with `queue-capacity=0` and a 30-thread pool was the worst profile in the
latest pass. The scheduler can submit hundreds of key tasks in one cycle. With zero queue capacity,
tasks beyond the active pool can be rejected immediately.

The logs showed `TaskRejectedException` and `RejectedExecutionException`, and the run ended with
p99 latency above 440 seconds. Prefer the default queue behavior unless you also change the scheduler
or reduce batch sizes enough to avoid task rejection.

### Keep Completed Records During Measurement

The benchmark keeps completed records in the table because the collector verifies completion and
latency from `outbox_record` rows.

Do not enable `delete-completed-records` for this performance-test scenario unless the collector is
changed to count completions elsewhere.

```yaml
namastack:
  outbox:
    processing:
      delete-completed-records: false
```

## Recommended Starting Points

For this local benchmark, use these as candidate profiles rather than absolute defaults.

### Lowest-Latency Candidate from the Latest Pass

```yaml
spring:
  threads:
    virtual:
      enabled: true

namastack:
  outbox:
    polling:
      trigger: fixed
      batch-size: 800
      fixed:
        interval: 25ms
    processing:
      delete-completed-records: false
      stop-on-first-failure: true
      executor-concurrency-limit: 30
    instance:
      heartbeat-interval: 1s
      stale-instance-timeout: 10s
      rebalance-interval: 1s
```

Run this with 4 consumer instances first, then repeat the test several times.

### Platform-Thread Candidate

```yaml
namastack:
  outbox:
    polling:
      trigger: fixed
      batch-size: 1000
      fixed:
        interval: 25ms
    processing:
      delete-completed-records: false
      stop-on-first-failure: true
      executor-core-pool-size: 16
      executor-max-pool-size: 32
    instance:
      heartbeat-interval: 1s
      stale-instance-timeout: 10s
      rebalance-interval: 1s
```

Run this with 6 consumer instances first. If database or scheduler churn is visible, also test the
4-instance, batch-800, 100ms-polling profile.

Then tune in this order:

1. Establish the highest producer rate that is stable with your real handler workload.
2. Test virtual threads with an explicit concurrency limit if handlers block on external I/O.
3. Test nearby batch sizes, for example `500`, `800`, and `1000`; keep the setting with the best tail latency and backlog slope.
4. Adjust poll interval only after batch size and instance count are stable.
5. Repeat the candidate run several times and compare p99 latency, backlog growth, and logs.
6. Watch heartbeat and rebalance logs. Re-registration warnings during load are a sign that the scheduler or database is overloaded.

## How to Interpret Results

A run should be considered healthy only when all of these are true:

- Producer actual rate is close to the target rate.
- Backlog growth after warm-up is near zero or negative.
- Backlog at producer stop is small enough to drain quickly.
- p95 and p99 latency stay within the application's SLA.
- Consumer logs do not show heartbeat re-registration, task rejection, or connection-pool starvation.

In the latest pass, 10000 records/s was possible, but only selected profiles were healthy. The safest
message from the rerun is not “one setting is always best”; it is that high-throughput settings need
to be validated with repeated runs on the target machine.
