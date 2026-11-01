# Outbox performance test

**Experimental branch:** this setup and its library optimizations are being evaluated together.

Measures the JDBC consumer from **this checkout** against PostgreSQL. PostgreSQL, consumers,
load generator, Prometheus, and Grafana run in Docker. No Maven Local publishing is needed.

## Run

Requires JDK 21+, Docker Compose v2, Python 3, and `curl`.
For the 150k/s reference setup, give Docker **32 GB RAM**, roughly **18 CPU cores**, and at least
**150 GB free disk** for a five-minute run. The database is dedicated to this benchmark and is reset
on every run. Do not point this setup at an application database.

```bash
cd namastack-outbox-performance-test
./run-performance-test.sh
```

The default is a one-minute screen at **150,000 records/s** with eight consumer JVMs. It builds the
current library automatically. For a five-minute confirmation:

```bash
./run-performance-test.sh --producer-duration=5m
```

For a smaller workload, change the rate and instance count:

```bash
./run-performance-test.sh --producer-rate=20000 --instances=4
```

The database cache defaults still target a 32-GB Docker environment. On a smaller machine, override
`PERF_PG_SHARED_BUFFERS` and `PERF_PG_EFFECTIVE_CACHE_SIZE`, lower the rate/instance count, and budget
768 MB heap per consumer plus the producer and monitoring services. Throughput will differ.

Use `--skip-build=true` only when no library or benchmark source changed. Run `./run-performance-test.sh --help`
for all options. There are no extra wrapper scripts or tuning profiles.

## What it measures

1. Starts an isolated PostgreSQL database and monitoring services.
2. Applies the **library's shipped PostgreSQL schema** and verifies every index definition.
3. Starts eight Java 21 consumers using virtual threads, strict ordering, 2,000 keys per poll,
   10-ms polling, 12 database connections per consumer, and completion batching (2,048 records,
   1-ms collection window, one writer). PostgreSQL multi-row completion updates are enabled.
4. Warms up with 250,000 records, then preloads four million completed records to represent history.
5. Produces exactly rate × duration records. Each key has ten records, kept in the same producer
   transaction. The empty payment handler measures outbox overhead, without external service latency.
6. Captures committed counts at producer stop, drains the remainder, and checks completion order for
   every record. A failed correctness check, missed producer rate, or unsustainable backlog fails the run.

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
The schema has the primary key plus two partial processing indexes; completed history is excluded
from the secondary indexes. The benchmark does not add or remove indexes beyond the checked-out schema.

For ordering verification, a temporary physical replication slot retains WAL until the audit finishes.
If the VM wall clock moves backward, the verifier checks the affected records' transaction commit
positions rather than accepting misleading timestamps. The slot is released on exit. Retained WAL
can exceed `max_wal_size`, which is why long runs need substantial free disk. This verifies persisted
completion order; it does not prove exactly-once external handler effects.

## Verified result

Library commit `5e3edced`, after the query-dialect refactor, strict mode, five minutes, on the
18-CPU/32-GB Docker setup:
**149,694 processed records/s**. Of 45,000,000 produced records, 44,916,923 were committed as completed
at producer stop and 83,077 remained. They drained in 0.63 s. The final audit checked every record:
zero missing completions, ordering violations, retries, or failures; p99 processing latency was 548 ms.
Run ID: `verify-150k-query-architecture-20261101`. Producer target met; capacity assessed as sustainable.

The simplified default command was also verified end to end: **149,624 records/s** over one minute,
all nine million records audited without violations, and capacity assessed as sustainable
(`clean-default-150k-20261101`). Results depend on hardware and handler work.

## Results and cleanup

The console prints produced, completed, pending, elapsed production time, and average processing rate.
Every run writes `reports/<run-id>/` with:

- `report.md`: throughput, producer compliance, backlog, latency, and graphs.
- `production-summary.json`: committed counts at producer stop.
- `completion-order.json`: full ordering audit.
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
