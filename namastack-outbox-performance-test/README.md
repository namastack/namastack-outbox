# Outbox performance test

**Current step:** JDBC query and index optimizations. Completion updates are synchronous;
completion batching is deliberately excluded.

Measures the JDBC consumer from **this checkout** against PostgreSQL. PostgreSQL, consumers,
load generator, Prometheus, and Grafana run in Docker. No Maven Local publishing is needed.

## Run

Requires JDK 21+, Docker Compose v2, Python 3, and `curl`.
For the reference setup, give Docker **32 GB RAM**, roughly **18 CPU cores**, and at least
**150 GB free disk** for a five-minute run. The database is dedicated to this benchmark and is reset
on every run. Do not point this setup at an application database.

```bash
cd namastack-outbox-performance-test
./run-performance-test.sh
```

The default is a one-minute screen at **20,000 records/s** with eight consumer JVMs. It builds the
current library automatically. For a five-minute confirmation:

```bash
./run-performance-test.sh --producer-duration=5m
```

For a smaller workload, change the rate and instance count:

```bash
./run-performance-test.sh --producer-rate=5000 --instances=4
```

The database cache defaults still target a 32-GB Docker environment. On a smaller machine, override
`PERF_PG_SHARED_BUFFERS` and `PERF_PG_EFFECTIVE_CACHE_SIZE`, lower the rate/instance count, and budget
768 MB heap per consumer plus the producer and monitoring services. Throughput will differ.

The default build first runs a JDBC integration test that blocks a completion commit and checks
that the next record of that key waits, including when the commit fails.
Use `--skip-build=true` only when no library or benchmark source changed; it also skips that check. Run `./run-performance-test.sh --help`
for all options. There are no extra wrapper scripts or tuning profiles.

## What it measures

1. Starts an isolated PostgreSQL database and monitoring services.
2. Resets the benchmark tables, applies the **library's shipped PostgreSQL schema**, and verifies
   every index definition. Existing-installation upgrades retain legacy indexes; that additive
   initialization path is covered separately by the JDBC index integration test.
3. Starts eight Java 21 consumers using virtual threads, strict ordering, 2,000 keys per poll,
   10-ms polling, and 12 database connections per consumer. Each completion uses the normal
   synchronous JDBC update; there is no completion batcher.
4. Warms up with 250,000 records, then preloads four million completed records to represent history.
5. Produces exactly rate × duration records. Each key has ten records, kept in the same producer
   transaction. The empty payment handler measures outbox overhead, without external service latency.
6. Captures committed counts at producer stop, drains the remainder, and checks completion timestamps
   for every record. A failed consistency check, missed producer rate, or unsustainable backlog fails the run.

The load generator uses PostgreSQL binary COPY with 16 connections and 2,560 records per transaction.
This prevents producer overhead from hiding consumer capacity. It does **not** measure one business
transaction per record or the public scheduling API. Set `PERF_PRODUCER_COPY=false` to use JDBC batch
inserts, or change `--producer-batch-size` and `--producer-workers` to model another workload.
With parallel producers, batch size must be a multiple of records per key so a key cannot span transactions.

Database-wide count scans run **after** production. During production the collector polls only the
small run table; report curves are reconstructed afterward. The producer-stop result uses an MVCC
snapshot of committed records, so it does not count an uncommitted completion as processed.
Processing timers remain enabled; duplicate database-backed state gauges are disabled.

## PostgreSQL tuning

The defaults live in [`postgresql.conf`](postgresql.conf), [`docker-compose.yml`](docker-compose.yml),
and [`sql/25_tuned_autovacuum.sql`](sql/25_tuned_autovacuum.sql). There are no alternative index layouts.

| Setting                           | Default                          | Purpose                                                                    |
|-----------------------------------|----------------------------------|----------------------------------------------------------------------------|
| Shared buffers / estimated cache  | 20 GB / 24 GB                    | Keep the active data and indexes in cache; sized for the reference machine |
| WAL buffers                       | 64 MB                            | Reduce WAL-buffer flush pressure                                           |
| WAL compression                   | LZ4                              | Reduce full-page WAL volume                                                |
| Maximum / minimum WAL size        | 32 GB / 4 GB                     | Avoid repeated checkpoints under heavy writes; not a disk-space limit      |
| Connections                       | 250                              | Cover consumer pools, producers, and monitoring                            |
| Work memory / JIT                 | 16 MB / off                      | Keep short processing queries predictable                                  |
| Autovacuum wake interval          | 10 s                             | Revisit frequently updated tables sooner                                   |
| Table vacuum / analyze thresholds | 2% / 1%, minimum 10,000          | Reclaim dead tuples and refresh statistics on the growing table            |
| Vacuum cost limit / delay         | 2,000 / 1 ms                     | Give maintenance enough I/O budget for this write rate                     |
| Background writer                 | 10 ms, 1,000 pages, multiplier 4 | Smooth dirty-buffer writes                                                 |
| PostgreSQL shared memory          | 1 GB                             | Support parallel post-run scans beyond Docker's default 64 MB              |

**Durability stays enabled:** `fsync`, `full_page_writes`, and `synchronous_commit` are all on.
The fresh schema has the primary key plus five secondary record indexes:

| Index | Scope | Purpose |
| --- | --- | --- |
| `(record_key, created_at, id)` with partition/status/retry metadata | Incomplete records | Ordered head selection and bulk loading |
| `(status, next_retry_at)` with partition/creation metadata | Incomplete records | Retries and pending/failed metadata |
| `(status, partition_no)` | All records | Global and partition status counts |
| `(record_key, status)` | All records | Key/status lookup and deletion |
| `(completed_at)` | Completed records | Ordered retention batches |

The two full-history lookup indexes omit per-record timestamps and permit PostgreSQL B-tree
deduplication. Pending/failed queries explicitly include `completed_at IS NULL`, including with generic
prepared plans. Retention's cutoff comparison permits the completed-only index. The JDBC plan tests
cover these access paths without forcing index scans. An operation affecting most records may still
correctly use a table scan; exact counts still need to read the matching index entries.

Upgrades retain old indexes. See [PostgreSQL index upgrades](../namastack-outbox-docs/docs/reference/database.md#postgresql-indexes-and-upgrades)
before comparing an upgraded installation with this fresh-schema benchmark. The runner uses the
checked-out schema exactly; it does not create a benchmark-only index layout.

For ordering verification, a temporary physical replication slot retains WAL until the audit finishes.
If the VM wall clock moves backward, the verifier checks the affected records' transaction commit
positions rather than accepting misleading timestamps. The slot is released on exit. Retained WAL
can exceed `max_wal_size`, which is why long runs need substantial free disk. This checks completion
timestamp consistency, including commit-position checks for backward timestamps. It does not prove
the commit order of every record or exactly-once external handler effects. Strict advancement after
a successful commit is covered separately by
[`JdbcOutboxCompletionOrderingTest`](../namastack-outbox-jdbc/src/test/kotlin/io/namastack/outbox/JdbcOutboxCompletionOrderingTest.kt).

## Validation and index trade-offs

The default 20k/s load checks this query/index-only step. It does not claim a 150k/s capacity for
synchronous completion updates. Completion batching remains a separate optimization.

The current branch passed the one-minute **20k/s** Docker check with synchronous completions:
1,200,000 produced, **1,198,132 completed at producer stop**, **1,868 pending**, and
**19,991 processed records/s**. The remainder drained in 0.05 seconds. All 1.2 million records passed
the completion timestamp audit, with zero failures or retries. Run ID:
`balanced-current-branch-20k-20261101`.

The experimental branch with the **same query/schema sources**, plus its existing completion
batcher, processed **99,932 records/s** during five minutes of 100k/s input: 30,000,000 produced,
29,982,051 completed at producer stop, and 17,949 pending. It used eight consumers and one completion
writer per consumer, with the shipped five-index layout. Run ID:
`balanced-five-indexes-100k-5m-20261101`. The remainder drained in **0.23 seconds** and all 30 million
records passed the completion timestamp audit, with zero failures or retries. The capacity check
passed. This establishes a tested **100k/s sweet spot**, not a 150k/s claim, for this indexed layout
with the experimental batcher on the reference machine.

The balanced layout above passed a one-minute screen in the experimental branch with its existing
completion batcher: **149,408 processed records/s**. The five-minute follow-up did not sustain that
rate: **109,612/s**, 32,918,188 of 45,000,000 completed at producer stop, 12,081,812 pending, and
123.38 seconds to drain. All 45 million records completed and passed the timestamp audit without
missing completions or violations; failures and retries were zero. The short screen alone was
therefore insufficient to establish capacity.

Run IDs: `balanced-five-indexes-150k-screen-20261101` and
`balanced-five-indexes-150k-5m-20261101`. Both used eight consumers and one completion writer per
consumer. These results include the experimental completion batcher, which is absent here.

A five-minute configuration trial with four consumers and two writers each achieved only 58,515/s
at 150k/s input. Its drain was stopped after recording the producer-stop counters, so it has no final
audit. The eight-consumer/one-writer configuration remains the verified choice. A partition-local
query experiment was also discarded after a slower screen; it is not included in the final code.

### Why replace the older indexes?

An earlier controlled comparison used identical consumer binaries, strict ordering, the same
PostgreSQL settings, and the existing completion batcher. Only the database index layout changed;
identifier lengths were matched for that pair. The balanced column is a separate follow-up, with
matching auxiliary query predicates, a rebuilt JAR, and a different run identifier; it is not part of
the identical-binary comparison.

| Five-minute result at 150k/s input | Earlier five-index layout | Balanced five-index layout | Lean two-index control |
| --- | ---: | ---: | ---: |
| Produced | 45,000,000 | 45,000,000 | 45,000,000 |
| Completed at producer stop | 26,282,300 | 32,918,188 | 44,970,090 |
| Pending at producer stop | 18,717,700 | 12,081,812 | 29,910 |
| Processing during production | 87,538/s | 109,612/s | 149,758/s |
| Final drain | 286.70 s | 123.38 s | 0.28 s |
| WAL through final snapshot | 114.7 GiB | 96.3 GiB | 82.1 GiB |
| Final record index storage | 20.7 GiB | 13.3 GiB | 9.0 GiB |

All three runs delivered and timestamp-audited all 45 million records without violations. The earlier
five-index/lean control used identical consumer JARs (SHA-256: `8264cbc9936e8fc129cf1d270f9e4838a246aae12b4a7f89a0fb8b4a4afce185`).
Run IDs: `transferred-fixes-five-indexes-150k-5m-20261101` and
`control-two-indexes-150k-5m-20261101xxxxxxxxxxx`.

The earlier full-history indexes included per-record creation/retry timestamps. Their maintenance,
WAL, and cache cost reduced write capacity; growing backlog also increased polling work. The lean
control omitted useful administrative/retention access paths. The current balanced layout instead
keeps narrow full-history lookup indexes, a targeted retention index, and two incomplete-only indexes.
It is a different five-index layout from the earlier slow result. No custom extended statistics are
created; normal PostgreSQL auto-analyze and fixture analysis remain enabled.

### Retention query check

An isolated PostgreSQL 17.11 test compared completed-record ID selection on one million completed
rows, with 100,000 expired. Both custom and generic prepared plans returned the same IDs.

| Index | No expired rows | Oldest 1,000 expired IDs | Index size |
| --- | ---: | ---: | ---: |
| Earlier five-index layout, without a retention index | 20.7–21.1 ms | 23.7–23.9 ms | — |
| Partial B-tree on `completed_at` | 0.025–0.030 ms | 0.193–0.212 ms | 21.4 MiB |
| BRIN on `completed_at` | 0.055–2.802 ms | 7.76–7.85 ms | 24 KiB |

The partial B-tree is now part of the shipped PostgreSQL schema. It reduced buffer accesses from
roughly 17,356 to 3 for an empty result and 23 for the bounded batch. BRIN is smaller but cannot
provide the requested ordering and depends on physical time correlation. These cached query timings
do not measure complete retention job throughput or concurrent retention under peak production.

## Results and cleanup

The console prints produced, completed, pending, elapsed production time, and average processing rate.
Every run writes `reports/<run-id>/` with:

- `report.md`: throughput, producer compliance, backlog, latency, and graphs.
- `production-summary.json`: committed counts at producer stop.
- `completion-order.json`: full completion timestamp audit.
- `execution.txt`, `indexes.json`, PostgreSQL snapshots, and logs: reproduction details.

A completed final drain alone is not evidence of sustainable throughput. Check the rate **during
production**, producer compliance, and backlog growth in the report.

To retain the monitoring services after the run:

```bash
./run-performance-test.sh --keep-monitoring=true
```

Grafana: <http://localhost:53000/d/namastack-outbox-performance>.
Consumers always stop on exit. The database volume is retained and reset at the next run.
To stop monitoring and delete benchmark data:

```bash
docker compose -p namastack-outbox-performance-test down -v
```

Reports stay on disk and are ignored by Git. After an uncatchable kill, ensure no run is active before
removing `runtime/run.lock`; the next run releases abandoned benchmark WAL slots.

A backlog-drain scenario is also available:

```bash
./run-performance-test.sh --mode=backlog-drain --records=1000000
```
