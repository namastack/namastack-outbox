# Namastack Outbox Performance Test

This standalone Gradle composite build measures Namastack Outbox throughput against PostgreSQL.
The primary `steady-state` mode continuously inserts payment events and verifies whether consumers
can keep up without a growing backlog. The `backlog-drain` mode measures recovery after downtime or
a burst.

## Prerequisites

- Java 21 (required by this build and the virtual-thread profile)
- Docker with Docker Compose
- `curl`

The composite build uses the outbox modules from the parent checkout; publishing snapshots to
Maven Local is not required. Use `JAVA_HOME` and `PATH` to select Java 21 for both Gradle and the
consumer processes.

If the default ports are occupied, set `PERF_POSTGRES_PORT`, `PERF_PROMETHEUS_PORT`,
`PERF_GRAFANA_PORT` and `PERF_POSTGRES_EXPORTER_PORT` before running the script.

## Current throughput search (2026-10-29)

The native JDBC profile passed a **five-minute 100000 records/s** test in the 32 GB, 18-CPU Docker
VM: **99901 committed records/s** during production, **0.586s p99 latency**, 21791 pending at
producer stop and **0.288s final drain**. All **30000000 events** completed in order with zero
failures, retries or consumer log issues. This is the highest sustained target established by this search.

The profile uses eight Java 21 consumers, 1024 virtual-thread workers per consumer, 256-row
completion groups with a 250us maximum wait, 1000-key reads, a 12-connection pool per consumer,
25ms polling, full-batch draining and the matching `(record_key,status,created_at)` index.
Four million completed records are retained initially; each key has ten events and producer
transactions contain 100 records. All timed JVMs and PostgreSQL run on the Docker network.

```bash
./run-max-performance-test.sh
```

The strongest target-compliant higher-rate screen committed **129145/s at a 130000/s target**
for 60 seconds. That variant used 640-record producer transactions and consumer JVM flags
`-XX:ActiveProcessorCount=4 -Xms256m -Xmx512m`. Runtime-container memory fell from roughly 6.3 GB to
4.8 GB in the JVM comparison. Four consumers could not sustain 130k with an equal total key budget;
twelve passed but had higher latency than eight. The 512-row completion groups, commit delay,
fillfactor 70, larger WAL budget and array completion parameters did not establish a higher
sustained rate. Arrays improved latency in one short pair but only changed throughput by 0.2%,
so that SQL remains experimental.

Other native short screens reached about 119000 committed/s at a 120000/s target and 133000/s
when a 140000/s producer missed its target. **The five-minute native 120000/s attempt failed**: backlog
reached 12.9 million and drain timed out after a query-plan regression. The short peaks do not
establish sustained capacity. A further five-minute 130k attempt with 640-record producer batches,
JVM limits and a 64 GB WAL budget also failed: 19.1 million of 39 million events were still pending
at producer stop. Custom and generic overload plans both selected a whole-table hash anti-join;
the tested memory and checkpoint settings did not remove this key-discovery bottleneck.

The separate **100000/s live recovery test passed**: a ninth consumer joined and acquired
29 partitions, then was killed during production. All 256 partitions belonged to surviving consumers
12.47 seconds later. All 12 million events completed in order with zero failures or retries; input
was 99970/s and committed throughput 99879/s. The test used the native profile with the measured JVM
limits and 100-record producer transactions. See the
[recovery evidence](reports/max-throughput-20261028/topology-result.json).

Durability (`fsync`, `synchronous_commit`, `full_page_writes`) stays on.

See the [confirmed five-minute report](reports/max-20261029-native100-confirm5m-5m/report.md),
[ordering verification](reports/max-20261029-native100-confirm5m-5m/completion-order.json), and
[all comparisons](reports/max-throughput-20261028/native-results.md). Exact options, artifact hashes,
server statistics and rejected experiments are retained with each run. Ordering verification reserves
WAL with a temporary physical replication slot until its checks finish, then releases it during
cleanup. This retains independent evidence when the VM clock moves backwards; it can require disk
space roughly proportional to the run's WAL generation. `PERF_RETAIN_VERIFICATION_WAL=false` disables
that retention, but missing WAL evidence makes a backwards-timestamp result fail closed. Parallel
ordering tests require the producer transaction batch to be a multiple of records per key; this
keeps each key within one producer transaction rather than introducing producer commit races.

## Earlier Comparison: Metadata-Only Completion and SQL Profiling

JDBC now completes retained records by updating processing metadata without mapping or serializing
the payload again. Retry/fallback metadata is retained; payload, context and routing fields remain
unchanged. Per-record transaction boundaries, failure handling and ordering remain the same.
Custom repositories inherit the new `updateCompletion` method through their existing `update`.
The core suite (652 tests) and selected JDBC/H2/PostgreSQL checks (78 tests) passed.

Paired tests kept SQL profiling, full-batch draining, the eight-consumer Docker profile, five-index
schema and 40000/s input target the same:

| Duration | Completion path | Processing during production | End backlog | p99 | Capacity |
| --- | --- | ---: | ---: | ---: | --- |
| 90s | Full row | 37096/s | 259889 | 8.00s | Fail |
| 90s | Metadata only | 38921/s | 96432 | 5.23s | Fail |
| 5m | Full row | 33021/s | 2092723 | 65.11s | Fail |
| 5m | Metadata only | 34901/s | 1528350 | 42.07s | Fail |

Metadata-only completion improved throughput by 4.9% in the short pair and 5.7% in the long pair.
This is one sequential pair at each duration, not a quantified estimate of variability. Both
long tests failed the unchanged capacity criteria. Earlier unprofiled runs reached about 39142/s;
they are not matched controls for these profiled measurements. The absolute rate difference
cannot be attributed solely to profiling or the completion change from these runs.

All 31200000 live records across the four new runs completed with zero failures, retries,
consumer log issues or persisted completion-order violations. Full-row control SQL is selected
only in the benchmark using `PERF_FULL_ROW_COMPLETION=true` (default false).

`PERF_PG_STAT_STATEMENTS=true` opts the tuned Docker benchmark into `pg_stat_statements`, resets
its counters immediately before production, and saves normalized query timing, block access and
WAL statistics at producer stop and after final drain. In the metadata-only five-minute run,
completion updates accumulated 4672 seconds of SQL execution across concurrent connections,
per-key reads 757 seconds, and key selection 283 seconds. These totals overlap across connections
and do not include all commit-wait time. Ten wait samples in the matched control showed WAL-write
waits as the largest active wait category. WAL generation per completion update remained around
1 KB: skipping serialization does not remove row-version and index maintenance costs.

Reproduce the profiled optimized run:

```bash
PERF_PG_STAT_STATEMENTS=true PERF_DRAIN_ON_FULL_BATCH=true \
  ./run-docker-performance-test.sh --profile=completion-metadata-profiled \
  --warmup-records=250000 --producer-workers=8 --producer-rate=40000 \
  --producer-duration=5m --run-timeout=10m
```

Add `PERF_FULL_ROW_COMPLETION=true` for the full-row control. Keep profiling the same on both
sides of comparisons. Summary CSV/JSON, per-statement comparisons, verification and an
implementation patch are in `reports/completion-optimization-20261028/`. Each individual run
also contains its full report, graphs, query snapshots, execution metadata and ordering checks.

## Previous Run: Full-Batch Draining at 40000/s

The five-minute Docker run with `polling.drain-on-full-batch=true` completed all 12000000
live records, with zero failures, retries, consumer log issues or persisted completion-order
violations. It did **not** establish sustainable 40000/s capacity or demonstrate a throughput
gain over the previous fixed-delay run.

| Metric | Previous fixed delay | Full-batch draining |
| --- | ---: | ---: |
| Actual input | 39994.66/s | 39993.74/s |
| Processing during production | 39147.85/s | 39142.41/s |
| Stabilized backlog growth | 312.18/s | 415.75/s |
| Backlog at producer stop | 254078 | 255439 |
| p99 latency | 6.56s | 8.88s |
| Final drain | 5.95s | 5.81s |
| Capacity | Fail | Fail |

The draining run exceeded both the 400/s backlog-growth limit and the 80000 end-backlog
limit. Throughput was effectively unchanged (-0.014%); p99 latency was higher in this run.
A single comparison does not isolate the cause: the feature also adds a fair assignment lock
and waits for submitted workers on rejection/interruption. A runtime database sample showed
WAL-write and buffer-lock contention. Both runs performed three checkpoints during production.

The remaining setup matches the previous run: eight Java 21 consumers in Docker, virtual threads,
20 workers each, pool size 28, 1000 keys per batch, 25ms base polling, ten records per key,
250000 warm-up records and four million retained completed rows. The repository query remains
unchanged and the updated five-index schema remains active.

Reproduce with:

```bash
PERF_DRAIN_ON_FULL_BATCH=true ./run-docker-performance-test.sh \
  --profile=drain-on-full-batch --warmup-records=250000 --producer-workers=8 \
  --producer-rate=40000 --producer-duration=5m --run-timeout=10m
```

The flag defaults to `false`. Reports, graphs, execution metadata, implementation provenance,
comparison and correctness evidence are in `reports/drain-full-batch-20261028-docker-40k-300s/`.

## Previous Baseline: Original Query with Updated Indexes

For this earlier baseline, the Kotlin repository and its query bindings were identical to Git HEAD.
Only the PostgreSQL index definitions changed: add `idx_outbox_record_incomplete_key_order` on
`(record_key,created_at,id) WHERE completed_at IS NULL`, and remove
`idx_outbox_record_status` and `idx_outbox_record_record_key_completed_created`.
The resulting outbox index set has five indexes including the primary key. The retained
`(status,next_retry_at)` index supports status lookups. Failed records and future retries remain
in the partial index, preserving predecessor barriers.

Both the shipped PostgreSQL schema and benchmark schema use these definitions. Existing
databases need a migration to remove the old indexes; schema initialization alone creates
missing indexes without dropping old ones. The benchmark reset applies the removals to its
persistent volume before loading fixtures.

Reproduce the index-only comparison with:

```bash
./run-docker-performance-test.sh --profile=original-query-index-only \
  --warmup-records=250000 --producer-workers=8 \
  --producer-rate=40000 --producer-duration=5m --run-timeout=10m
```

The Docker, PostgreSQL, application and load settings match the earlier 40000/s tests. Runtime
SQL and index-catalog evidence, repository bytecode hashes and the comparison are stored in
`reports/index-only-20261028/`. The control measurements below used the previous schema.

The five-minute index-only test met the 40000/s input target (39994.66/s) and processed
39147.85/s during production. All 12000000 live records completed without failures, retries,
consumer log issues or persisted completion-order violations. P99 latency was 6.56s and drain
time was 5.95s. It failed the capacity check because end backlog was 254078, above the 80000
limit; its 312.18/s backlog-growth rate was within the configured 400/s tolerance.

| 40000/s target, five minutes, same Docker/PG/application settings | Processing during production | End backlog | p99 | Capacity |
| --- | ---: | ---: | ---: | --- |
| Original query, original six indexes | 35098/s | 1469579 | 42.16s | Fail |
| Original query, updated five indexes | 39148/s | 254078 | 6.56s | Fail |
| Earlier modified query, same five indexes | 39932/s | 17316 | 6.96s | Pass |

The index-only configuration improves this comparison but did not reproduce the earlier
40000/s pass. The query source and compiled repository class remain unchanged. The requested
index layout remains active in the shipped schema and benchmark; 72 JDBC/PostgreSQL tests passed.

## Earlier Control: Original Library and Original Indexes in Docker

The JDBC repository, its tests and shipped PostgreSQL schema have been restored to Git HEAD.
The benchmark uses the original six outbox indexes, including the primary key. The added
incomplete-record index is removed from existing benchmark volumes, and index consolidation
has been removed. Docker runtime support, PostgreSQL tuning, fixture accounting and verification
remain in the test harness.

Run the original library at 20000/s:

```bash
./run-docker-performance-test.sh --profile=original-library-docker --producer-duration=5m
```

For comparison with the earlier 40000/s workload:

```bash
./run-docker-performance-test.sh --profile=original-library-docker \
  --warmup-records=250000 --producer-workers=8 \
  --producer-rate=40000 --producer-duration=5m --run-timeout=10m
```

Both use eight Java 21 consumers inside Docker, 20 workers per consumer, pool size 28, 1000 keys
per batch, 25ms polling, the 32 GB Docker VM, the tuned PostgreSQL settings and four million
retained completed rows. Completion-order checks and durability settings remain enabled.
The comparison and source provenance are stored in `reports/stock-code-20261028/`.

The original-library 20000/s confirmation passed for five minutes: all 6000000 live records
completed, actual input was 19998.89/s, processing during production 19998.14/s, end backlog 225,
backlog growth 0.37/s and p99 latency 0.083s. There were no failures, retries, consumer log issues
or persisted completion-order violations. The 72 original JDBC/PostgreSQL checks also passed.
This result requires no query or index changes to the library.

At 40000/s, the original library met the input target (39996.09/s) but processed 35097.98/s
while production was active. End backlog was 1469579, backlog growth 4426.96/s, final drain
42.20s and p99 latency 42.16s. The capacity check failed. All 12000000 live records eventually
completed without failures, retries, consumer log issues or persisted completion-order violations.
Thus 20000/s is confirmed with the original code/schema under this Docker/PostgreSQL profile;
the previous 40000/s result did not reproduce after reverting the code/index changes together.

| Original library, original six indexes | Duration | Processing during production | End backlog | p99 | Capacity |
| --- | --- | ---: | ---: | ---: | --- |
| 20000/s target | 5m | 19998/s | 225 | 0.083s | Pass |
| 40000/s target | 5m | 35098/s | 1469579 | 42.16s | Fail |

## Historical Results with Query Code Changes

The measurements below used query/index changes that have now been removed. They describe the
previous revision, rather than the original-library comparison above. Historical per-run
`execution.txt` files preserve the old configuration; their index-removal options are no longer
supported by the current harness.


The highest validated target was **40000 records/s for five minutes** with the reduced index
profile. The default **20000/s with the full index set** also passed for five minutes, with
substantially lower latency. Both confirmations started with four million completed fixtures;
all 18 million live records across the two runs completed without failures, retries, consumer
log issues or persisted completion-order violations.

| Profile | Duration | Target | Actual input | Processing during production | End backlog | p99 | Capacity |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Default, full indexes, 4 producer connections | 5m | 20000/s | 19999/s | 19998/s | 400 | 0.05s | Pass |
| Reduced indexes, 8 producer connections | 5m | 40000/s | 39990/s | 39932/s | 17316 | 6.96s | Pass |
| Reduced indexes, 8 producer connections | 90s | 50000/s | 49982/s | 40161/s | 884207 | 19.28s | Fail |

The default confirmation delivered 6000000 live records into a table that reached ten million
rows. Backlog growth was 0.10/s, final drain was 0.02s and p50/p95/p99 latency was approximately
0.02/0.04/0.05s. The maximum-rate profile has a different latency tradeoff; it is not the default.

The 32 GB Docker Desktop tests found a substantial difference between Java clients on macOS
and clients on the database's Docker network. A 20000/s screen with host-connected clients
processed 17626/s. Increasing PostgreSQL buffers to 8GB produced 17202/s in the next host-connected
screen. Keeping those PostgreSQL settings and moving Java onto the Docker network processed
19994/s, with only 200 records pending at producer stop and p99 latency of 0.22s. A subsequent
30000/s screen also passed. These are results for the tested topology, not an isolated measurement
of network latency or a universal PostgreSQL ceiling.

Run the Docker profile with Java 21 selected for the build and 32 GB allocated to Docker Desktop:

```bash
./run-docker-performance-test.sh --producer-duration=5m
```

The default target is 20000/s with eight consumers and the full shipped index set. The script
builds the local checkout, resets the dedicated benchmark database, and runs consumers, producer
and collector in an ARM64 Java 21 container on PostgreSQL's Docker network. It requires no local
PostgreSQL installation. The Java image digest is pinned; execution reports record the resolved
image and actual runtime version. Native-host execution remains available through
`run-tuned-performance-test.sh`.

The profile uses 20 virtual-thread workers and a pool of 28 per consumer, 1000 keys per cycle,
25ms polling, 10 records per ordering key, and four producer connections committing 100 records
per transaction. It preloads four million completed records and excludes them from live counts.
The default measurement remains a 90-second screen unless a longer duration is supplied.

PostgreSQL uses 8GB shared buffers, a 24GB cache estimate, 16MB work memory, a 16GB WAL budget,
2GB minimum WAL, 64MB WAL buffers, LZ4 WAL compression, JIT off, and the previously tested
table vacuum settings. The cache estimate is a planner input, rather than a memory reservation.
Commit durability, full-page writes, strict ordering and completed-record retention remain
enabled. Completion persistence still commits each record separately.

The historical maximum-rate profile used reduced indexes, eight producer connections and
250000 warm-up records. Its original command is preserved in the archived run's `execution.txt`.

With eight producer connections, that profile passed its 90-second screen: actual input was
39987.23/s, processing during production 39698.52/s, end backlog 25992, backlog slope -125.34/s
and p99 latency 6.55s. All 3600000 records completed in persisted order. The latency spike and
short duration required longer confirmation before treating 40000/s as an operating target.

The five-minute confirmation then passed at 40000/s: all 12000000 live records completed with
zero failures, retries, consumer log issues or persisted completion-order violations. Actual
input was 39990.10/s, processing during production 39932.40/s, end backlog 17316, backlog slope
-241.42/s and p99 latency 6.96s. The table reached 16 million records including retained fixtures.
The producer window included three requested checkpoints and 28.08GB of WAL, with no temporary
spills or full WAL buffers. These measurements used the reverted library/index changes.

A 50000/s screen with the same settings met the input target (49982.16/s) but processed only
40161.15/s, ended with 884207 pending records and failed the capacity criteria. All 4500000
records eventually completed in persisted order. Thus 40000/s is the highest five-minute target
validated in this pass; 50000/s is an unsuccessful upper probe. These results do not establish
the precise maximum between those targets.

The former reduced-index profile removed the status-only index, whose leading column is covered by
`(status,next_retry_at)`, and the old `(record_key,completed_at,created_at)` predecessor index,
which is replaced by the partial incomplete-key index. The retained key/creation index supports
key-history reads. That optional benchmark schema profile has now been removed. The paired 40000/s screens generated about 9.94GB of WAL
with the full index set and 8.14GB with the reduced set. Neither screen established 40000/s
capacity: the first missed the input-rate target and the second exceeded the end-backlog limit.

The larger warm-up exposed a setup bug: pollers could cache an empty-table discovery plan before
the bulk load became visible. In a temporary-table reproduction, a cached query timed out after
three seconds on 20000 pending rows; refreshing statistics reduced it to about 11ms. The tooling
now runs `ANALYZE outbox_record` inside the bulk-trigger transaction before publishing the rows.
PostgreSQL invalidates prepared plans when their planner statistics change
([PostgreSQL PREPARE documentation](https://www.postgresql.org/docs/17/sql-prepare.html)). Trigger
timing includes the analysis. The failed 250000-record warm-up is retained as a setup failure,
with no capacity result. Java-client cleanup also enables one-second server checks for abandoned
connections, so a terminated benchmark cannot leave its long query consuming CPU indefinitely.

The Docker profile verifies completion timestamps per key after performance snapshots. It checks
every live row and rejects missing rows, missing completion timestamps and decreasing completion
timestamps in `(created_at,id)` order. Positive and negative SQL fixtures verify the checker.
This validates persisted completion order; it does not verify external business side effects,
duplicate delivery after crashes or live rebalance behaviour. The handler remains empty.

Every case, including failed targets and failed setup, is listed in the local
`reports/tuning-ram32-20261028/index.html`, with CSV/JSON alongside it. Per-run directories contain
execution settings, seven graphs, PostgreSQL snapshots, logs and `completion-order.json`.

## Earlier Screens with an 8 GB Docker VM

Run the opt-in tuned profile with Java 21 selected:

```bash
./run-tuned-performance-test.sh
```

The highest passing target in the earlier 8 GB pass used **eight consumers at 17000/s for three
minutes**. Reproduce that configuration with:

```bash
PERF_DB_POOL_SIZE=28 ./run-tuned-performance-test.sh \
  --instances=8 --producer-rate=17000 --producer-duration=3m
```

All 3060000 live records completed with zero failures, retries or consumer log issues. Actual
producer rate was 16996.33/s, processing during production 16819.03/s, end backlog 31922, backlog
growth 167.10/s and p99 latency 2.40s. This passed narrowly: the permitted backlog slope was
170/s and end-backlog limit was 34000. Positive backlog growth means this is not evidence of
indefinite stability. It is the highest passing target tested here, rather than an absolute
maximum. The default below keeps the six-consumer 16000/s profile, which had more headroom.

This resets the dedicated benchmark database. Defaults use alternate ports (PostgreSQL 55432,
Prometheus 59090, Grafana 53000, exporter 59187), six consumers, 20 virtual-thread workers per
consumer, pool size 30, 1000 keys per cycle and 25ms polling. Production runs for 90 seconds at
16000 records/s with 100-record producer transactions and four producer connections. The first
30 seconds are excluded from backlog-growth regression. Four million completed fixtures simulate
retained history; they are excluded from live completion counts, latency and throughput.

The PostgreSQL settings are opt-in through `docker-compose.tuning.yml`: 8GB maximum WAL, 1GB
minimum WAL, 16MB WAL buffers, LZ4 WAL compression, 1GB shared buffers, 16MB work memory, JIT off,
and 10-second autovacuum checks. Table vacuum/analyze thresholds are in
`sql/25_tuned_autovacuum.sql`. `plan_cache_mode` stays `auto`. The default JDBC URL enables
`reWriteBatchedInserts=true`; supplying `PERF_DB_URL` replaces that URL. This optimization affects
the benchmark's batch producer; it does not turn individual application inserts into batches.
These values describe this Docker benchmark, rather than universal production defaults.

The initial tuned retained-table screen passed at 17000/s: actual producer rate 16995.94/s,
processing during production 16958.36/s, end backlog 3383, backlog slope -110.54/s and p99 latency
1.43s. All 1530000 live records completed with zero failures or retries. This is a short screening
result. A three-minute confirmation at 17000/s subsequently failed: processing averaged 16564.55/s,
end backlog reached 77953 and backlog growth was 291.88/s. All 3060000 records eventually completed
without failures or retries. The earlier five-minute maximum below remains a separate measurement.

| Screen | Target | Processing during production | End backlog | p99 | Capacity |
| --- | ---: | ---: | ---: | ---: | --- |
| Original PostgreSQL settings, fresh table | 17000/s | 10062/s | 622720 | 120.79s | Fail |
| WAL settings only, fresh table | 17000/s | 10779/s | 558560 | 120.35s | Fail |
| Full PostgreSQL settings, fresh table | 17000/s | 16915/s | 7152 | 0.63s | Pass |
| Full settings, four million retained rows | 17000/s | 14813/s | 196336 | 12.66s | Fail |
| Targeted query fix and JDBC batch rewriting, retained rows | 17000/s | 16958/s | 3383 | 1.43s | Pass |
| Four consumers, settled retained fixtures | 18000/s | 16014/s | 178411 | 11.68s | Fail |
| Six consumers, settled retained fixtures | 18000/s | 17282/s | 64036 | 4.21s | Fail |
| Six consumers, settled fixtures, 50µs commit delay | 18000/s | 17533/s | 41520 | 2.79s | Fail |
| Same commit delay, polling reduced to 1ms | 18000/s | 17375/s | 55803 | 3.65s | Fail |
| 50µs commit delay, settled fixtures | 17500/s | 17174/s | 28925 | 2.39s | Fail (borderline) |
| Same 17500/s screen, original table vacuum thresholds | 17500/s | 12489/s | 450530 | 28.12s | Fail |
| Tuned preset, three-minute confirmation | 17000/s | 16565/s | 77953 | 5.24s | Fail |
| Tuned preset, three-minute confirmation | 16000/s | 15972/s | 4690 | 0.71s | Pass |
| Eight consumers, pool 28, three-minute confirmation | 17000/s | 16819/s | 31922 | 2.40s | Pass (narrow) |

The settings were introduced in bundles, so these results do not isolate the individual effects
of shared buffers, work memory, JIT and autovacuum. The comparison includes negative results:
global `force_custom_plan` and both 20000/s attempts timed out waiting for the producer and did
not establish capacity. Increasing concurrency to 30 and pool size to 36 did not fix that.
The complete local comparison, including intermediate screens and statistics, is in
`reports/tuning-20261028/index.html` with CSV/JSON alongside it.
The 17500/s screen exceeded its 175/s backlog-growth limit by 1.09/s; its end backlog was within
the 35000-record limit. That narrow miss is not evidence of a precise universal ceiling.

The six-consumer 16000/s confirmation delivered all 2880000 live records over three minutes,
starting with four million vacuumed completed fixtures. Actual producer rate was 15997.56/s,
processing during production 15971.51/s, backlog growth 19.16/s, end backlog 4690 and p99 latency
0.71s. There were no failures, retries or consumer warnings/errors. This is the selected
six-consumer operating point; 17000/s did not pass the longer confirmation.

The evidence identifies several separate limits:

- The original database forced six WAL checkpoints in the baseline producer window. The larger
  WAL eliminated requested checkpoints and full WAL buffers in the initial tuned 90-second
  screens, but that alone did not restore capacity. Each six-consumer three-minute confirmation
  included one requested checkpoint. At 17000/s, backlog grew after that checkpoint began;
  16000/s passed despite the checkpoint load. A larger WAL budget might spread checkpoint work
  further, but a short run that simply postpones the next checkpoint would not validate that.
- The full PostgreSQL profile eliminated the observed temporary-file spills. It passed on a
  fresh table but slowed with retained history, exposing a separate query-planning problem.
- Polling now embeds its closed enum status in SQL, keeping NEW/COMPLETED selectivity visible to
  the planner. Keys, partitions and times remain bound parameters. The predecessor index covers
  incomplete records, including failed records and future retries. Global custom planning was
  slower in its screen and is not selected.
- Exact full-table benchmark counting adds load. Increasing the sampling interval from 5s to
  15s reduced that interference; it did not independently produce a passing retained-table run.
- Above the passing rate, active sessions mostly waited on WAL writes/commit. In the instrumented
  failed 20000/s run, WAL sync averaged 2.37ms under load, versus about 61µs in an idle scratch-file
  probe. Six-consumer 18000/s samples had no pending Hikari requests or timeouts; GC pauses were
  small. More connections and JVM heap are not supported as fixes by those samples.
- Each successful event persists completion in its own transaction. The six-consumer 18000/s
  window generated about 4.18GB of WAL and 50200 WAL syncs. Retention, index maintenance and
  vacuum therefore remain costs after discovery is fixed. Completion batching would require a
  separate design and failure/replay analysis.
- Restoring the original table autovacuum thresholds reduced processing from 17174/s to
  12489/s in the paired 17500/s screens. The original-threshold stop snapshot estimated over
  1.1 million dead rows. Less frequent maintenance did not improve this retained-table workload.

The tuned preset sets `PERF_SETTLE_RETAINED_RECORDS=true` to vacuum/analyze fixtures before measurement. Those
runs are explicitly labeled as settled: they exclude fixture insertion's initial vacuum demand
and are not identical to the earlier fixture screens. Set it to `false` to reproduce those
earlier fixture preparations. Automatic maintenance remains enabled
during production. Short screens expose retained-table planning quickly, but do not reproduce
hours of dead tuples, checkpoints, retention and varying handler costs.

Arguments override the preset defaults, for example `--producer-duration=3m --retained-records=2000000`.
Environment variables override PostgreSQL settings, concurrency and pool size. The runner records
effective settings, table options, producer-window PostgreSQL statistics and logs with every run.
Durable commit settings remain on, completed records remain retained and strict ordering remains
enabled. JDBC repository/PostgreSQL ordering checks passed (75 tests); collector accounting checks
passed (3 tests). The benchmark handler itself is empty and does not verify downstream side effects.

## Documented Virtual-Thread Profile

The best profile in the previous tuning pass used 4 instances, a concurrency limit of 30 per
instance, 800 keys per batch and 25ms polling. Reproduce it against the current checkout with:

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
  --sample-interval=5s
```

Database-backed outbox state metrics are disabled for this benchmark; the collector measures state
directly. Handler and processing timers remain enabled for Prometheus throughput graphs.

## Capacity Search on 2026-10-28

The highest validated rate in this search was **15000 records/s for five minutes**, using 4
virtual-thread consumers with concurrency limited to **20 per instance**, batch size 800 and 25ms
polling. All 4500000 records completed without failures or retries. Processing during production
averaged 14962.51 records/s, backlog growth was 24.44 records/s, end backlog was 10836 records, and
p99 latency was 0.96s. This passed the existing capacity criteria; backlog growth was positive.

To reproduce, use the virtual-thread command above with
`NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT=20`, `--producer-rate=15000`, and
`--producer-duration=5m`.

| Profile | Target rate | Duration | Capacity | p99 latency |
| --- | ---: | --- | --- | ---: |
| 4 instances, concurrency 30, batch 800 | 14500/s | 2m | Sustainable | 0.15s |
| 4 instances, concurrency 30, batch 800 | 15000/s | 2m | Unsustainable | 6.92s |
| 4 instances, concurrency 20, batch 800 | 15000/s | 5m | Sustainable | 0.96s |
| 4 instances, concurrency 20, batch 800 | 16000/s | 2m | Unsustainable | 8.53s |
| 6 instances, concurrency 30, batch 1000 | 15000/s | 2m | Sustainable | 0.24s |
| 6 instances, concurrency 30, batch 1000 | 20000/s | 2m | Unsustainable | 11.47s |
| 6 instances, concurrency 20, batch 1000 | 17500/s | 5m | Unsustainable | 10.81s |
| 6 instances, concurrency 20, batch 1000 | 17000/s | 5m | Unsustainable | 289.96s |
| 6 instances, concurrency 20, batch 1000 | 16000/s | 5m | Unsustainable | 42.80s |

The first six-consumer attempt exhausted PostgreSQL's Docker shared-memory allowance. That run is
excluded from healthy-capacity comparisons. The Compose setup now sets `shm_size: 1gb`; the
six-consumer rows above are clean reruns with that allowance. Reports record the effective limit
in `execution.txt`.

The five-minute runs exposed substantial variability: 17000/s performed much worse than 17500/s.
During that slowdown, key-discovery queries ran for multiple seconds; captured plans used parallel
full-table scans and a hash anti-join. These observations limit confidence in a precise ceiling.
15000/s is the highest validated five-minute target in this search, not proof of an absolute
maximum. Short runs and additional consumers did not establish a higher sustained rate.

The runner now rejects consumer logs containing `WARN` or `ERROR` and saves the matching lines in
`consumer-log-issues.log`. SQL selection errors can occur before a record attempt, so zero record
failures and retries alone do not establish a healthy capacity result.

The machine was an Apple M5 Max with 64 GiB host memory; Docker had 18 CPUs and about 7.75 GiB
memory. The test used Java 21.0.12.1 and PostgreSQL 17.11. The existing production-example Docker
stack remained running. This is a measured boundary for the tested profiles and durations, not a
universal maximum. The empty handler, 10 records per key and 100-record producer transactions were
unchanged, and immediate batch draining was not enabled.

The confirmation run is `max-20261028-15000-vt20-confirm-5m`. The local comparison report, CSV and
JSON are in `reports/max-throughput-20261028/`; individual run directories contain the full reports,
graphs, consumer logs and execution settings.

## Incomplete-Record Index Validation

The PostgreSQL schema now includes `idx_outbox_record_incomplete_key_order` on
`(record_key, created_at, id) WHERE completed_at IS NULL`. The retained-data query probe improved
from 42–77ms to 27–30ms, with an index-only predecessor lookup. JDBC repository and PostgreSQL
schema/ordering checks passed (74 tests, no failures or skips).

The five-minute validation at 17000 records/s with 6 virtual-thread consumers, concurrency 20,
batch 1000 and 25ms polling still failed the capacity check. Run
`max-20261028-17000-6inst-b1000-vt20-indexfix-5m-01` completed all 5100000 records without failures
or retries, but averaged 15757.54 processed records/s, ended with 372178 pending records and had
p99 latency of 27.84s.

Run-specific PostgreSQL logs recorded 37 WAL-triggered checkpoints in five minutes, with a median
start interval of 7.96s. The database was still configured with 128MB shared buffers, 4MB work
memory and a 1GB WAL limit. This points to further database tuning; the index alone did not make
17000 records/s sustainable. Proposed tuning values and captured diagnostics are in the local
`reports/config-analysis-20261028/` directory. The short screens documented above subsequently tested those proposals.

## Primary Test

```bash
./run-performance-test.sh \
  --mode=steady-state \
  --instances=4 \
  --producer-rate=3000 \
  --producer-duration=10m \
  --producer-workers=4 \
  --producer-batch-size=1 \
  --records-per-key=1 \
  --warmup-records=25000
```

The producer plans exactly `producer-rate × producer-duration` records. Records are never silently
dropped when the local producer cannot keep up. Instead, production takes longer and the report
marks the producer result as `TARGET MISSED`.

`--producer-batch-size=1` models one event per committed business transaction. Increase the value
for import-style workloads. `--producer-workers` controls the number of parallel JDBC connections.

## Event And Key Model

Each generated record contains a small `PaymentRequestedEvent` payload with a payment ID, customer
ID, amount, currency and creation timestamp. The default `--records-per-key=1` assigns one
deterministic `payment:<id>` key per record. This is the baseline for maximum independent
parallelism and is reproducible across runs.

Use values such as `--records-per-key=10` or `--records-per-key=100` as separate scenarios to
measure the effect of ordered processing for repeated keys. Random UUID keys are not needed for the
baseline: deterministic keys are realistic enough for hashing while making test runs comparable.

## Tooling Architecture

The command-line tooling uses small packages with explicit responsibilities. The root package only
contains the entry point and dependency wiring.

```text
command -> run.PerformanceTestService -> store.PerformanceStore <- jdbc
collector ---------------------------> store.PerformanceStore <- jdbc
collector -> report.PerformanceReportWriter <- report.MarkdownPerformanceReportWriter
```

- `command` parses command-line arguments, creates request objects and prints results.
- `run` contains the performance-test lifecycle, requests, results and run state.
- `record` creates deterministic outbox benchmark records.
- `store` defines database access ports.
- `collector` samples run state and coordinates report creation.
- `jdbc` contains SQL, JDBC connections and PostgreSQL COPY implementations.
- `report` contains measurement models and Markdown, JSON, CSV, Prometheus and SVG reporting.
- `internal` contains small module-internal utility functions.

## Results

Steady-state reports separate three questions:

- `Correctness result`: Were all records processed after the final drain without retries or errors?
- `Producer rate result`: Did the local producer reach the requested write rate?
- `Capacity assessment`: Did consumers keep up while the target rate was actually applied?

The capacity assessment uses stabilized backlog growth after `--measurement-warmup=30s` and the
remaining backlog at producer stop. Limits can be overridden with `--max-backlog-growth-rate` and
`--max-end-backlog`.

## Recovery Test

```bash
./run-performance-test.sh \
  --mode=backlog-drain \
  --instances=4 \
  --records=1000000 \
  --records-per-key=1
```

## Reports And Monitoring

Each run creates a self-contained Markdown report:

```text
reports/<run-id>/
├── report.md
├── parameters.json
├── execution.txt
├── samples.csv
├── prometheus-queries.json
├── collector.log
├── graphs/
└── logs/
```

Use `--keep-monitoring=true` to leave PostgreSQL, Prometheus and Grafana running. Grafana is
available at `http://localhost:3000/d/namastack-outbox-performance`.
