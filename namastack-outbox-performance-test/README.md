# JDBC performance tests

Run the library from your current checkout against a disposable database. The build, database,
producer and consumer JVMs all run in Docker. No host JDK or Maven Local publishing is needed.

## Run a test

Requirements: Docker with Compose v2+, Bash and Git. Allocate at least 8 GiB RAM to Docker and
allow disk space for database images, Gradle dependencies and WAL/redo logs. On Windows, use WSL2.
The first build downloads JDK 17/21 and Gradle dependencies; later builds reuse a Docker cache.

```bash
cd namastack-outbox-performance-test
./run-performance-test.sh
```

This offers **20,000 records/s for one minute** to PostgreSQL, using four consumer JVMs,
strict ordering, virtual threads, 1,000 keys per poll and ten records per key. It first seeds
one million completed history records, waits for partition assignment and warms up. The handler
has no business-side work. Completion and deletion use this branch's normal library code.
A target rate is a load request, not a throughput guarantee.

Every run uses a fresh, isolated Compose project and the JDBC module's actual schema and indexes.
There is no copied benchmark schema, extra index, SQL override or automatic upgrade step.
The runner removes its containers, database volumes and network afterward, including on failure.
Reports and the shared Gradle cache are retained. Run tests sequentially for comparable results.

```bash
./run-performance-test.sh --database=mysql --rate=10000
./run-performance-test.sh --database=mariadb
./run-performance-test.sh --database=sqlserver
./run-performance-test.sh --database=oracle --rate=5000
./run-performance-test.sh --database=h2 --rate=5000

# Longer confirmation after a short screen looks promising:
./run-performance-test.sh --rate=20000 --duration=5m

# Selection, bulk loading and deserialization, with key/order checks:
./run-performance-test.sh --mode=read --duration=10s

# Committed input capacity without consumers:
./run-performance-test.sh --mode=producer --rate=100000 --duration=1m

# Small smoke run:
./run-performance-test.sh --history=10000 --rate=1000 --duration=10s --instances=1
```

Read-only fixtures include failed heads, future retries and completed predecessors. Read rates
repeat a fixed corpus and are **not completed-record throughput**. To vary backlog/key density:

```bash
./run-performance-test.sh --mode=read --history=0 --records=1000000 \
  --records-per-key=2 --readers=1 --batch-size=200 --duration=10s
```

Consumers poll with the fixed trigger every 10 ms by default. The adaptive trigger halves its delay
(down to the minimum) after a poll that fills the whole batch, and doubles it (up to the maximum)
after a poll that returns at most a quarter of the batch:

```bash
./run-performance-test.sh --poll-trigger=adaptive --poll-min-interval=1ms --poll-max-interval=10ms
./run-performance-test.sh --poll-trigger=fixed --poll-interval=5ms
```

Use `--help` for all options. `--batch-size` limits keys per poll, not records within a key.
Producer transactions always contain whole keys; `--producer-batch-size` must be a multiple of
`--records-per-key`. `--strict=false` exercises non-strict processing. `--delete=false` retains
completed records. `--skip-build=true` is useful only if artifacts already match your source.

## Read the results

The runner prints `reports/<run-id>/summary.md`. That directory also contains:

- `processing-current.json`: produced, processed, remaining and failed counts, elapsed time,
  average processed records/s, producer-target and capacity-screen flags.
- `read-current.json` or `producer-current.json` for the other modes.
- `environment.json`: options, effective consumer settings, revision/dirty state and artifact hashes.
- `source-status.txt`, `source.patch`: checkout status and tracked changes against HEAD;
  untracked file contents are not included in the patch.
- `database.json`, `compose.yml`, `docker.txt`: engine/driver versions and container settings.
- `build.log`, `consumer-*.log` and step logs for diagnosing failures.

Warm-up results have their own `warmup-summary.md`. A failed main measurement does not leave
a warm-up result in `summary.md`.

For deletion runs, processed means committed input minus physically remaining run rows. For
retention runs it means persisted `COMPLETED` rows. History and warm-up records are excluded.
The final count runs after production stops, while consumers are still working; its duration
is included in the conservative average. Inspect `snapshotMilliseconds` for expensive snapshots.

A capacity-screen pass requires the producer target (within 1%), processing within 3%, no failed
records and pending backlog of at most two seconds of offered input. Inspect consumer logs too.
A one-minute pass is a short screen, not proof of sustained maximum throughput or handler-side
ordering. Library integration tests cover ordering; load tests keep the real scheduler and retry
rules enabled. Compare runs on the same hardware, settings, key distribution and retention mode.
Generated reports and previous local experiments are ignored by Git.

## Database settings

Defaults fit a development machine rather than requiring the earlier 32 GiB benchmark profile.
Only the selected database runs. PostgreSQL uses 2 GiB shared buffers, a 4 GiB planner cache estimate,
64 MiB WAL buffers, WAL compression and a 32 GiB WAL target to reduce checkpoint pressure.
Autovacuum/analyze thresholds are lower for the insert/delete workload. **fsync, full-page writes
and synchronous commit stay enabled.** These are benchmark server settings, not library defaults.

MySQL/MariaDB use 2 GiB buffer pools and redo logs. MySQL's purge-lag setting is retained from the
insert/delete experiments. SQL Server uses a 4 GiB memory limit and read-committed snapshot isolation
on its dedicated test database. Its benchmark sends VARCHAR parameters to match the ASCII fixture
and VARCHAR schema. It runs under amd64 emulation on Apple Silicon. Oracle Free has edition-specific
resource limits. H2 uses a persistent TCP database with immediate write flushing. These differences
mean cross-engine figures are not hardware-independent rankings.

For a larger Docker VM, override memory without editing files:

```bash
PERF_PG_SHARED_BUFFERS=20GB PERF_PG_EFFECTIVE_CACHE_SIZE=24GB \
  ./run-performance-test.sh --rate=20000
```

Other controls are `PERF_MYSQL_BUFFER_POOL_SIZE`, `PERF_MARIADB_BUFFER_POOL_SIZE`,
`PERF_SQLSERVER_MEMORY_MB`, `PERF_H2_HEAP`, `PERF_DB_POOL_SIZE` (12 per consumer),
`PERF_EXECUTOR_CONCURRENCY_LIMIT` (2048), `PERF_CONSUMER_JAVA_OPTIONS` and `PERF_TOOL_JAVA_OPTIONS`.
The producer uses PostgreSQL binary COPY or JDBC batches elsewhere; this accelerates input only.
There is no completion/deletion batching added by the test setup.

For an existing application database, see the [index upgrade guide](../namastack-outbox-jdbc/INDEX_UPGRADE.md).
The performance runner does not connect to application databases.

## Troubleshooting and cleanup

Builds need network access to container and Maven/Gradle registries. If a run fails, open the last
step's log in its report directory. A failed producer target can mean the generator or database is
saturated; do not interpret that run as a successful test of the requested rate.

`--keep-database=true` leaves that run's database available for inspection and prints its cleanup
command. The project name is also saved in `reports/<run-id>/project.txt`. There are no published
host ports. Remove retained databases before running more benchmarks to avoid resource contention.
If a runner is forcibly killed, use the saved project name:

```bash
docker compose -p <project-name> --profile <database> down --volumes --remove-orphans
```

The shared run lock lives at `${TMPDIR:-/tmp}/namastack-jdbc-performance-test.lock`. Remove a stale
lock only after confirming no performance runner is active. To reclaim the build cache when no
build is running, remove Docker volume `namastack-outbox-perf-gradle-cache`.
