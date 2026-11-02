# Outbox performance tests

Runs the checked-out library against real databases in Docker. The database, load generator and
consumers all run in Docker; Gradle builds the artifacts locally. Nothing is published to Maven Local.

This branch optimizes **JDBC reading and indexes**. Completion updates remain synchronous.
The experimental completion batcher lives in a separate branch; its processing rates must be reported separately.
See [measured results and their limits](RESULTS.md) for the six-database comparison.

## Run

Requires JDK 21+, Docker Compose v2, Python 3 and `curl`. The reference machine gives Docker
32 GB RAM and 18 CPUs. Long high-rate PostgreSQL runs need substantial free disk (about 150 GB).
These runners reset only their dedicated benchmark databases.

```bash
cd namastack-outbox-performance-test

# End-to-end PostgreSQL test: eight consumers, strict ordering, 20k input/s for one minute.
./run-performance-test.sh

# Isolate the MySQL read path: key selection, bulk loading and payload decoding.
./run-jdbc-performance-test.sh --database=mysql

# Real processing on another database, with completed rows deleted.
./run-jdbc-performance-test.sh --database=mysql --mode=processing \
  --rate=20000 --duration=1m --delete=true
```

Supported values: `postgres`, `mysql`, `sqlserver`, `oracle`, `mariadb`, `h2`.
Run one benchmark at a time. The portable runner shares a host lock across worktrees because they
use the same Docker project and database volumes. Use `--help` for options. Both runners build the current checkout by
default; `--skip-build=true` is only for unchanged binaries after a successful build and correctness check.
The PostgreSQL runner also runs the blocked-completion ordering test before building.

Use short screens while tuning, then longer confirmations:

```bash
./run-jdbc-performance-test.sh --database=mysql --duration=20s --readers=8
./run-jdbc-performance-test.sh --database=mysql --duration=1m --readers=8
./run-performance-test.sh --producer-rate=20000 --producer-duration=5m
```

For controlled read comparisons, reuse a fixture and write a separate result label:

```bash
./run-jdbc-performance-test.sh --database=mysql --run-id=mysql-comparison --label=initial
./run-jdbc-performance-test.sh --database=mysql --reuse=true --run-id=mysql-comparison \
  --readers=4 --label=four-readers
```

Reuse also applies missing indexes from the current shipped schema. To compare fresh index layouts,
start a new run without `--reuse=true`; initialization recreates the benchmark tables.

## What the tests measure

**Read mode** seeds one million completed records, collects normal database statistics, then adds
256,000 mixed-state records with ten records per key. Failed heads and future-retry heads block 20%
of keys in strict mode. Other keys contain completed predecessors or a future retry inside the page.
History uses separate `history:` keys, representing inactive completed streams. This workload does
not measure large completed histories on repeatedly reopened keys. Eight readers own disjoint sets
of the 256 partitions. Each repeatedly selects up to 2,000 eligible
keys, bulk-loads their pages, and decodes payloads. The first iteration checks the expected keys and
every record ID in each page. Future retries remain in pages for the worker to evaluate.

The result is **repeatedly loaded records/s**, not uniquely processed records/s. JSON reports separate
selection and loading latency. There is no explicit read warm-up: JIT compilation and the initial
correctness pass occur in the measured loop. JVM startup and fixture construction are outside it.
The read fixture uses a serialized String payload; processing mode uses the payment event handler.
`--strict=false` measures non-strict eligibility; `--decode=false` skips decoding after the initial page audit.

**Processing mode** starts eight real consumer JVMs using virtual threads, strict ordering, 2,000 keys
per poll, a 10-ms polling interval, and a 12-connection pool per consumer. A three-second warm-up is
fully drained before measurement. The empty payment handler measures library overhead. Parallel
producers keep all records of a key in one transaction, pace committed batches toward the requested
input rate, and stop claiming new work at the time limit. In-flight transactions may finish later;
the report includes actual producer duration, rate and whether the requested rate was reached.

One database snapshot after producer completion counts remaining run rows. Retained completions use
persisted `COMPLETED` status. With deletion, completed count is committed input minus physically
remaining run rows. The snapshot is taken after both the full requested duration and all producer commits. The
conservative processing rate includes the snapshot query time. This mode
does not reconstruct deleted completion timestamps or assert that deleted records had zero retries.
It does not replace the scheduler/JDBC tests that verify advancement only after a successful commit.

The PostgreSQL-specific runner adds monitoring, a final drain, timestamp auditing, producer-rate
compliance and backlog-growth checks. See [PostgreSQL details](POSTGRESQL.md).
Neither load generator measures the public scheduling API or one business transaction per record.

## Database settings

Schemas and indexes come directly from the **checked-out JDBC module's classpath resources**.
No benchmark-only indexes or extended statistics are installed. Automatic initialization is additive;
only the explicit benchmark reset recreates tables.

| Database | Benchmark settings |
| --- | --- |
| PostgreSQL | 20 GB shared buffers, 24 GB cache estimate, 64 MB WAL buffers, LZ4 WAL compression, 32 GB maximum WAL size and more frequent vacuum/analyze. `fsync`, full-page writes and synchronous commit remain on. |
| MySQL / MariaDB | 16 GB InnoDB buffer pool and 4 GB redo capacity; commit durability is retained. MySQL batch inserts are rewritten by the driver. |
| SQL Server | Developer edition, 20 GB memory cap and snapshot-based read committed isolation in the dedicated database. Bulk-copy inserts use binary temporal values. Unicode parameters remain enabled. |
| Oracle | Oracle Free, row prefetch 1,000 and LOB prefetch 512 characters for the small benchmark payloads. These are transport hints, not truncation limits. Larger application payloads need different LOB tuning. |
| H2 | Persistent TCP database, 2 GB cache, 8 GB server heap, no delayed writes. The database stays open between tooling commands. These results are not embedded/in-memory H2 measurements. |

Defaults live in [docker-compose-jdbc.yml](docker-compose-jdbc.yml),
[postgresql.conf](postgresql.conf), and [the table maintenance settings](sql/25_tuned_autovacuum.sql).
The PostgreSQL-specific monitoring stack uses [docker-compose.yml](docker-compose.yml).
The disposable Oracle benchmark user clears dropped fixtures from its recycle bin, so repeated
resets do not consume its data quota. [Oracle Free's resource limits](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinw/licensing-restrictions.html)
are 2 CPUs, 2 GB RAM and 12 GB user data. SQL Server runs as an amd64 container, including under
emulation on Apple Silicon. These local results are not a hardware-normalized engine ranking.
Image tags can be overridden with `PERF_POSTGRES_IMAGE`, `PERF_MYSQL_IMAGE`, `PERF_SQLSERVER_IMAGE`,
`PERF_ORACLE_IMAGE`, `PERF_MARIADB_IMAGE` and `PERF_JAVA_IMAGE`. Reports include detected server and driver versions.

## Results and cleanup

The portable runner writes `reports/<run-id>/` containing:

- `database.json` and `fixture.json`: server/driver versions and the seeded workload.
- `execution-<label>.txt` and `parameters-<label>.json`: artifact hashes, Docker resources, requested settings and non-secret tuning overrides.
- `read-<label>.json`: selected keys, loaded records, elapsed time and median/p95 query-stage latency.
- `processing-<label>.json`: committed input, actual producer rate, processed/pending/failed counts and conservative processing rate.
- Build, initialization, warm-up and consumer logs.

Check producer compliance and remaining backlog before calling a processing rate sustainable.
A missed target remains a valid measurement and does not change the portable runner's exit code;
query failures, incorrect page contents and failed setup do fail the command.
The PostgreSQL runner writes its richer `report.md`, production summary and completion audit to the
same reports directory. Historical measurements and their limits are preserved in [POSTGRESQL.md](POSTGRESQL.md).

Consumers and the selected portable database stop on exit. Volumes remain for fixture reuse.
To remove all portable benchmark databases:

```bash
docker compose -p namastack-jdbc-performance-test -f docker-compose-jdbc.yml --profile '*' down --volumes
```
