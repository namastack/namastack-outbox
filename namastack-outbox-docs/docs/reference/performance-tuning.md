---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Performance Tuning
sidebar_position: 6
---

# Performance Tuning

The October 2026 tuning pass adds optional JDBC bulk reads and PostgreSQL completion batching.
The native profile passed a five-minute 100000/s test with 99901 committed records/s, 0.586s p99
latency and 0.288s final drain. All 30 million events completed in order with zero failures or
retries. Short screens reached about 119000 committed/s, but the five-minute 120000/s attempt
failed after key discovery switched to a whole-table hash anti-join. A 130000/s short screen passed
with 640-record producer transactions, but a five-minute repeat with a larger WAL budget also failed.

A separate 100000/s recovery test joined a ninth consumer, verified that it acquired 29 partitions,
then killed it during production. All 256 partitions were reclaimed by live consumers within 12.47s;
all 12 million events completed in order with zero failures or retries. Use the sustained result
when choosing a starting rate. Exact reports and configuration comparisons are in the performance
module's README and generated artifacts.

## Workload and measurement

These measurements use Java 21 virtual threads, PostgreSQL 17.11 and eight consumers on the same
Docker network. Docker Desktop has 18 CPUs and 32 GB memory. The handler is empty; each key has ten
events. Producer transactions contain 100 events, using eight producer connections and JDBC batch
insert rewriting. Each run starts with four million retained completed records and a 250000-record
warm-up. Handler I/O, payload size, key skew, concurrent application traffic and storage performance
will change capacity.

Completion throughput during production is calculated from a database snapshot of committed rows
at producer stop. A capacity pass requires actual input to reach at least 99% of the requested rate,
stabilized backlog growth no higher than 1% of the target rate per second, and stop backlog no higher
than two seconds of input. Every accepted run also checks total completions, failures, retries,
consumer log errors and persisted completion order. Reports keep capacity and correctness separate:
an overloaded run may eventually complete correctly while failing capacity.

## Native JDBC candidate profile

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    hikari:
      maximum-pool-size: 12
      minimum-idle: 5
namastack:
  outbox:
    polling:
      batch-size: 1000
      record-fetch-batch-size: 1000
      max-records-per-key: 0
      drain-on-full-batch: true
      trigger: fixed
      fixed:
        interval: 25ms
    processing:
      executor-concurrency-limit: 1024
      delete-completed-records: false
      stop-on-first-failure: true
    instance:
      heartbeat-interval: 1s
      stale-instance-timeout: 10s
      rebalance-interval: 1s
    observability:
      metrics:
        enabled: false
    jdbc:
      completion-batching:
        enabled: true
        max-batch-size: 256
        max-wait: 250us
```

These settings passed the five-minute 100000/s test on this dedicated benchmark workload; they are
not new library defaults.
The library keeps bulk reads and completion batching disabled by default. Use the corresponding
[configuration reference](./configuration.md) when enabling them. A large worker limit permits many
independent keys to wait for a shared completion commit; it does not require 1024 database connections.
Bound handler concurrency separately if handlers call a service with lower capacity. The benchmark
disables database state metric scans and retains completed rows because its collector verifies
completions and latency from those rows. Production retention and metrics need their own measurement.

Bulk reads remove one database round trip per selected key and decode payloads only when needed.
Completion batching combines writes from independent keys in one transaction. Each worker waits
for the commit before advancing its key. Scheduler batches retain their partition assignments until
all submitted workers finish, and the fair assignment lock allows a queued rebalance between batches.
Application transactions retain their original completion path rather than being detached into the
batch writer. Failed commits stop progression; delivery remains at least once and handlers still
need to tolerate replay after a crash.

## PostgreSQL and indexes

The Docker profile uses `shared_buffers=8GB`, `effective_cache_size=24GB`, `work_mem=16MB`,
`wal_buffers=64MB`, `wal_compression=lz4`, `max_wal_size=16GB`, `min_wal_size=2GB`, `jit=off`,
and tuned autovacuum. `effective_cache_size` is a planner estimate, not allocated memory.
`fsync`, `synchronous_commit` and `full_page_writes` remain on. These memory settings assume a
32 GB Docker VM with room for all consumers and other services.

The PostgreSQL schema replaces `(record_key, created_at)` with
`(record_key, status, created_at)`. The bulk read filters by both key and `NEW` status; the new index
allows direct matching instead of combining a key index with the status index. It retains five
indexes including the primary key. The partial predecessor index remains necessary for strict
ordering around failed records and future retries. Existing databases need the
[index migration](./configuration.md#bulk-record-reads-and-postgresql-completion-batching); automatic schema initialization
does not remove old indexes.

## Reproduce and tune

From `namastack-outbox-performance-test`, use Java 21 and run:

```bash
./run-max-performance-test.sh
```

The helper builds from this checkout, runs producer and consumer JVMs inside Docker, applies the
matching index and verifies completion order. It resets the dedicated benchmark database. Use a
unique run ID when comparing settings; reports include execution parameters and effective server
settings. Override rate and duration explicitly, for example:

```bash
./run-max-performance-test.sh --producer-rate=100000 --producer-duration=90s
```

Use 60–90 second screens to reject poor candidates, then repeat the strongest configuration for at
least five minutes and test node joins and recovery under load. Watch committed throughput, stop
backlog, backlog slope, latency and PostgreSQL waits together. A producer that misses its target
does not establish capacity at the requested rate. More workers, more instances or larger connection
pools can increase contention. Earlier tests showed query latency rising sharply as backlog grew;
longer polling intervals did not resolve that overload.

The June 2026 results used a different code path and host configuration and peaked near 10000/s.
The performance module keeps the historical runs for comparison; their settings are not the current
maximum-throughput profile.

For platform-thread executors, allow a queue sized for submitted key tasks; a zero-capacity queue
with a large scheduler batch caused task rejection in the earlier tests. Watch heartbeat
re-registration, pool starvation and rebalance logs under load, and apply your application's p95/p99
latency limits in addition to the benchmark capacity thresholds.
