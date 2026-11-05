# SQL benchmark harness for `outbox_record`

Measures the SQL the JDBC repository really sends (and the JDBC/JPA repositories themselves) for the scenarios in
`../02-scenarios.md`, on identical deterministic data, for any variant (worktree + optional DDL / per-query overrides).

```
harness/
  bin/db.sh              start|stop|status|rm|wipe <dialect>   (docker, fixed limits)
  bin/cleanup.sh         stop+remove all harness containers (--wipe: also volumes, h2 files)
  bin/variant-build.sh   build a worktree's jars (api/core/jdbc/jpa) -> build/variants/<variant>.jars
  bin/extract-catalog.sh extract catalog/<variant>/<dialect>/<id>.sql from the worktree's real code
  bin/bench              SQL-level runner (Kotlin, plain JDBC): gen prepare bench writecost concurrency churn equiv
  bin/repo-bench         repository-level runner (real JdbcOutboxRecordRepository / JpaOutboxRecordRepository, PostgreSQL)
  bin/compare.py         diff two result dirs into a markdown table with relative change
  catalog/               one .sql file per query id, per variant and dialect (generated, committed)
  sql/  repo/            Gradle sub-projects (Kotlin, JDK 25 toolchain), built on first use
../results/<variant>/<dialect>/<scenario>/   timings.csv  plans/  env.txt  (+ writecost.csv, concurrency-*.txt, churn.txt, repo-jdbc/, repo-jpa/)
../results/equiv/<dialect>/<scenario>/<a>-vs-<b>.md
```

All commands are run from anywhere; the scripts `cd` into `harness/`. Paths below are relative to `harness/`.
Needs: Docker, JDK 25 (harness toolchain 25), network for the first Gradle build. Worktree jars are built with
`--init-script gradle/jdk25.init.gradle.kts`, which switches the project's Java 17 toolchain to 25 without touching the worktree
(bytecode stays Java 17 through the project's `release = 17` / `jvmTarget = 17`).
The harness never modifies a worktree (only its `build/` directories change when jars are built).

## Quick start (MariaDB, S1, variants `mdb-main` and `mdb-opt`)

```bash
./gradlew --console=plain -q :sql:installDist                       # SQL runner; build before starting lanes, never while lanes run
bin/extract-catalog.sh mdb-main /home/azamojski/outbox-wt-mdb-main   # worktree jars (JDK 25 init script) + catalog/mdb-main/<dialect>/*.sql;
bin/extract-catalog.sh mdb-opt  /home/azamojski/outbox-wt-mdb-opt    #   the first call also builds repo/, which compiles against build/variants/mdb-main.jars
source bin/lane.sh 1                                               # lane env (container, port, CPU pinning); omit for the single default container
bin/db.sh start mariadb
bin/bench gen   --dialect mariadb --scenario S1                     # deterministic snapshot (database "snap", seed 42), per lane
bin/bench bench --dialect mariadb --variant mdb-main --worktree /home/azamojski/outbox-wt-mdb-main --scenario S1 --prepare --groups HOT,WRITE,MON
bin/bench bench --dialect mariadb --variant mdb-opt  --worktree /home/azamojski/outbox-wt-mdb-opt  --scenario S1 --prepare --groups HOT,WRITE,MON
bin/compare.py ../results/mdb-main/mariadb/S1 ../results/mdb-opt/mariadb/S1
HARNESS_SAMPLE_ORDERED=1 bin/bench equiv --dialect mariadb --a mdb-main --b mdb-opt \
  --worktree-a /home/azamojski/outbox-wt-mdb-main --worktree-b /home/azamojski/outbox-wt-mdb-opt --scenario S1
bin/db.sh stop mariadb; bin/cleanup.sh                              # cleanup.sh removes every harness container of every lane
```

Parallel lanes (see `bin/lane.sh`): each lane has its own container, port, volume and snapshot marker, so each lane runs its own `gen`.
Two lanes must never write the same result directory (use distinct variants or labels). Run the commands of a lane in a subshell
(`( source bin/lane.sh 2; bin/db.sh start mariadb; ... )`) so lane variables do not leak into another lane's commands.

## Concepts

- **Snapshot** (`gen`): the scenario data is generated once per dialect into schema/database `snap`, table `rec`
  (no keys, no indexes). A new `gen` replaces it. Only one snapshot per dialect exists (disk).
- **Run schema** (`prepare`, or `bench --prepare`): for a variant, schema `run` is dropped and rebuilt: DDL from
  `<worktree>/namastack-outbox-jdbc/src/main/resources/schema/<dialect>/outbox-tables.sql` (or `--ddl <file>`), data copied
  from the snapshot (`INSERT ... SELECT`), secondary indexes created after the load on PostgreSQL and H2 (inline on the other
  dialects), statistics refreshed (`ANALYZE` / `ANALYZE TABLE` / `UPDATE STATISTICS ... FULLSCAN` / `DBMS_STATS` / `ANALYZE`).
  Use `--schema <name>` to keep several variants side by side (the equivalence check uses `eq_a`, `eq_b`).
  **Re-run `prepare` before every `bench`, `writecost`, `concurrency` or `churn`**: `writecost`, `concurrency` and `churn` commit changes;
  `bench` itself rolls back every write/delete (S9 `churn` and S10 `concurrency` are meant for a fresh schema).
- **Variant** = `--variant <name> --worktree <path> [--ddl <file>] [--queries <dir>]`. `--queries` is a directory of `<id>.sql`
  files overriding single catalog entries (headers `-- maxrows:` etc. are inherited from the base entry when omitted).
  Experiments: copy the base `catalog/<variant>/<dialect>/` as the starting point, change the files, pass `--queries`.
  Experiments with other jars: `bin/extract-catalog.sh exp-3 /home/azamojski/outbox-wt-mdb-exp-3`; the repository-level runner
  takes the worktree's jars via `bin/repo-bench exp-3 ...`.
- **Catalog entry** (`catalog/<variant>/<dialect>/<id>.sql`): header lines `-- id/group/kind/maxrows/source`, then the SQL with named
  parameters, exactly as built by the repository (whitespace collapsed by `toSingleLine`). `maxrows` is how the repository limits
  rows: `none`, `batchSize`, `one`, `retention` (limit = 1000). The runner reproduces **exactly** what each variant sends:
  `main` has no `LIMIT` in SQL and calls `Statement.setMaxRows`; `v1` binds `:limit` in SQL (where the dialect has a limit clause)
  **and** also calls `setMaxRows`. A new `PreparedStatement` per execution (like `JdbcClient`), collections expanded to `?,?,...`.
  `main` extraction reads the private query fields of `JdbcOutboxRecordRepository`; `v1` calls `JdbcOutboxRecordQueries.of(dialect, table)`
  (reflection on the built jar). Extraction is dialect-complete (main: same SQL for all six dialects).
- **Query ids** are those of `02-scenarios.md`; additionally `count_statuses_all` (v1, all three statuses) and, for update ids,
  `upd_complete` / `upd_retry` / `upd_fail` share SQL but bind different values. Not applicable ids are written as `n/a` rows.
- **Parameters** (`sql/.../Params.kt`): `now = T0`, owned partitions (`--owned all|mod4|1,2,3`), batch size (`--batch`, default 10),
  retention cutoffs `T0-7d` (completed) / `T0-30d` (failed), limit 1000. `pages` gets the keys returned by `keys_strict` (fallback:
  `keys_nonstrict`, then sampled due keys), like the scheduler. Single-key / single-partition / id parameters are sampled from the
  loaded data with plain SQL. S4 batch-100 runs: `--batch 100 --label S4-b100`; S8: `--owned mod4` (+ `--label S8-S2` ...).
  `find_pending_all`/`find_failed_all` run only on S1/S3 (override: `--all-admin`).
- **Timing**: warmup 5 + 20 measured (`--warmup`, `--reps`); if the first run is > 2 s: 1 warmup + 5 measured and a `note`.
  Reads run in autocommit and fetch every column of every row (`getString`). Writes/deletes run in a transaction that is rolled back;
  the timer stops before the rollback (no commit cost). `rows` = rows returned, or the update count for writes.
  `timings.csv`: `query_id,median_ms,p95_ms,min_ms,max_ms,rows,reps,note`.
- **Plans** (`plans/<id>.*`, `--no-plans` to skip), one capture per query with the first parameter set, writes inside a rolled-back
  transaction:
  PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON|TEXT)` with real bind parameters (`.json`, `.txt`);
  MySQL `EXPLAIN ANALYZE` (falls back to `EXPLAIN FORMAT=TREE` for single-table UPDATE/DELETE); MariaDB `ANALYZE FORMAT=JSON`;
  SQL Server `SET STATISTICS XML ON` actual plan (`.xml`) and `SET STATISTICS IO ON` messages (`.io.txt`);
  Oracle `gather_plan_statistics` + `DBMS_XPLAN.DISPLAY_CURSOR(... 'ALLSTATS LAST')`; H2 `EXPLAIN ANALYZE`.
  Non-PostgreSQL engines get literals instead of bind parameters. A failed capture writes `<id>.error.txt`.
  **Plans show the statement without the client-side row cap**: `setMaxRows` is applied by the driver (pgjdbc: portal row limit, i.e. the
  plan is for the full result and execution stops after N rows; other drivers differ), so a plan for a `main` query can show more work
  than the capped execution does, as in production.
- **env.txt**: date, variant, worktree git sha and dirty files (excluding `docs`), DDL path and sha256, query override dir, scenario
  parameters, snapshot seed, image and image id, container limits, DB version, driver version, JVM, host, non-default PostgreSQL
  settings, then table/index sizes and index count (`--- sizes ---`).
- **Data generator** (`sql/.../Gen.kt`): seed 42, `T0 = 2026-01-01T12:00:00Z`, real hashing (`Hasher` = MurmurHash3 x86-32 seed 0 via
  commons-codec like `PartitionHasher`; `bin/repo-bench <variant> hashcheck` verifies 20 004 keys against the worktree's
  `PartitionHasher` class), rows emitted in `created_at` order (heap order of a live system), timeline per key strictly increasing with
  ~2 % ties. Scenarios S1, S2, S2L, S3, S4, S5, S6, S7 are generated (row counts exact; status counts as in `02-scenarios.md`, S5: the
  FAILED/future heads replace the first of a key's 2 000 NEW rows). S8 = S2/S4 data with `--owned mod4`; S9 = `churn` on S2 data;
  S10 = `concurrency` on S2 data. MySQL/MariaDB get second-truncated timestamps. PostgreSQL loads with `COPY`, the others with JDBC
  batches (SQL Server/Oracle bulk paths are not implemented).
  S2L (10M rows, 200 B payload) needs ~12 GB (snapshot + one run schema) on PostgreSQL: run only with nothing else on disk.

## Other commands

- `bin/bench writecost <variant args> [--n 5000 --threads 4]`: committed single-row insert latency/throughput (1 and N threads),
  committed `upd_complete` latency, size reports before/after (`writecost.csv`, `sizes-*.txt`; index count/sizes also in `env.txt`).
- `bin/bench concurrency <variant args> --pollers 1|4 [--seconds 60 --rate 500|0 --producers N --idle-ms 100 --tag t]`: pollers own
  disjoint partitions (`p % pollers == i`) and loop keys query -> pages -> `upd_complete` per due record; a producer inserts at
  `--rate` rec/s (0 = unthrottled, 4 threads). Virtual clock = `T0 + elapsed` so new rows are due. Output `concurrency-<tag>.txt`:
  processed rec/s, inserted rec/s, p50/p95 of keys, pages, update, insert.
- `bin/bench churn <variant args> [--inserts 500000]` (S9): inserts, processes (30 % delete-on-complete, 2 % FAILED, 10 % with 1-3
  retries), retention deletes of ~`inserts` oldest COMPLETED, **no manual VACUUM/ANALYZE**; `churn.txt` has sizes and
  `n_dead_tup` (PostgreSQL) before and after.
- `bin/bench equiv --dialect d --a <variant> --b <variant> --worktree-a W --worktree-b W [--ddl-a/--ddl-b/--queries-a/--queries-b]
  --scenario S1 [--no-prepare]`: builds `eq_a`/`eq_b` from the same snapshot and compares every read query (ordered where the contract
  has an order: keys, pages per key, finds; counts and MIN values; `count_statuses` against the three `countByStatus` of the other
  variant) and the effect (update count and resulting row/status counts) of every write/delete (rolled back). Verdicts PASS / WARN
  (limited result differs only through created_at ties, or the id set differs only at the limit boundary) / FAIL / N/A / SKIP. Exit code 2
  on FAIL or ERROR; report in `../results/equiv/...`.

## Repository-level runner

`bin/repo-bench <variant> bench --engine jdbc|jpa --dialect postgres --worktree W --scenario S1` instantiates the worktree's real
`JdbcOutboxPersistenceFactory.create(...)` (JDBC) or `JpaOutboxRecordRepository` on a Hikari pool (4 connections) with a transaction manager,
calls every repository method (warmup/reps as above) and records **every statement prepared through the data source** with its
`setMaxRows` (`repo-<engine>/sql.txt`, last repetition of each method; for JPA this is the Hibernate SQL). Results: `repo-<engine>/timings.csv`.
The classes are compiled against the `main` jars and run against the variant's jars (`bin/repo-bench <variant>` puts that variant's
`namastack-outbox-{api,core,jdbc,jpa}` jars first on the classpath), so variants never touch `mavenLocal`.
Extra ids: `delete_completed_batch` / `delete_failed_batch` (`deleteCompletedRecords` / `deleteFailedRecords`, rolled back).
Differences to the SQL level: `insert` and `delete_by_id` are committed (JPA flushes `persist`/`remove` only at commit; cleanup deletes the
bench rows afterwards), so they include commit and the extra JPA statements; counts report `rows = 1`; read methods include row mapping
(payload deserialisation with a trivial serializer) and, for `find_*Records`, full object construction.

## Container settings (bin/db.sh)

Images as in the integration tests: `postgres:18-alpine`, `mysql:9.2`, `mariadb:12`, `mcr.microsoft.com/mssql/server:2022-latest`,
`gvenzl/oracle-xe:18-slim-faststart`; H2 in-process (file `build/h2`). Limits `--cpus=4 --memory=4g` (`HARNESS_CPUS`, `HARNESS_MEM`),
recorded in every `env.txt`. Ports on 127.0.0.1: 15432 postgres, 13306 mysql, 13307 mariadb, 11433 sqlserver, 11521 oracle; data in docker volumes
`outbox-bench-<dialect>-data`.
Changes from image defaults: PostgreSQL `--shm-size=512m` (Docker default 64 MB), nothing else (default `shared_buffers=128MB`, `work_mem=4MB`, `jit=on`;
the actual non-default settings are listed in `env.txt`); SQL Server `MSSQL_MEMORY_LIMIT_MB=3072` (so it stays inside the 4 GB limit);
MySQL/MariaDB/Oracle none. Client sessions run in UTC (`-Duser.timezone=UTC`, MySQL/MariaDB `time_zone='+00:00'`, Oracle `ALTER SESSION`).
The default credentials are throw-away (`bench`).

## Known limits

- Validated on this host on 2026-11-05 with JDK 25 only: both Gradle modules build, `hashcheck` passes (0 of 20 004), catalog
  extraction through `variant-build.sh` with the JDK 25 init script works (worktree jars keep Java 17 bytecode, class major 61), and a
  lane-1 MariaDB S1 `gen` + `bench --prepare --groups HOT,WRITE,MON` ran end to end (container, port, cpuset and snapshot marker per
  lane recorded in `env.txt`). Not yet validated: two lanes at once, `writecost`/`concurrency`/`churn`/`equiv` on MariaDB.

- Inherited from the PostgreSQL study, where it was validated on PostgreSQL end to end and on MySQL and MariaDB at the SQL level
  (S1, S2, S8-S2). The MariaDB study validates again on S1 (phase 2) before any measurement.
- The SQL-level numbers use one JDBC connection (no pool, no Spring). Network/driver round trips are included.
- `writecost` records WAL volume on PostgreSQL only; the MariaDB equivalents (InnoDB redo bytes, pages written) are a phase 2 task.
- The repository-level runner is PostgreSQL only; a MariaDB JDBC mode is a phase 2 task.
- Row-limit behaviour of `setMaxRows` is the driver's; the harness does not force anything.
- `gen` for MySQL/MariaDB is JDBC-batch based: 1M rows take several minutes.
- Concurrency pollers use the catalog SQL (exactly what the variant sends) but not the scheduler's thread pool.

## Options (summary of the inherited runner)

- `bench bench`: `--prepare`, `--groups HOT,WRITE,MON` (restrict to catalog groups), `--only id1,id2`, `--owned all|mod4|<list>`
  (`S8-*` labels = `--owned mod4`, `S16-*` = `--owned 0,16,...,240`), `--batch N`, `--label L`, `--results DIR`, `--no-plans`,
  `--all-admin`, `--ddl FILE`, `--queries DIR` (overlay of single `<id>.sql` files on the base catalog), `--catalog-variant NAME`
  (base catalog when `--variant` is an experiment name), `--schema NAME`, `--seed N`, `--warmup/--reps`.
- Per-query limit: `--query-limit-s N`, `--limit-from <timings.csv> --limit-factor 2 --limit-min-s 60` (stopped executions are recorded,
  the run continues). `bench limitcheck --dialect d --query-limit-s 3` verifies that the limit can stop a query on that database.
- Slow-query cap: first execution > 2 s -> 1 warmup + 5 reps; > 30 s -> 1 execution, non-executing plan (MariaDB `EXPLAIN FORMAT=JSON`).
- Parameter samples use `ORDER BY 1` by default (`HARNESS_SAMPLE_ORDERED=0` disables), so variants with different indexes sample the
  same rows.
- MySQL/MariaDB `prepare` creates the secondary indexes after the data load (`ALTER TABLE ... ADD INDEX`), same final index list.
- `bench dropruns --dialect d` drops `run`, `eq_a`, `eq_b` (frees disk). `HARNESS_ID_VERSION=7` generates UUIDv7 ids (out of scope here).
- `--pages-client-sort`, `--pg-*`, `--capture-plans`, `analyze`: PostgreSQL-oriented options of the inherited runner.
- New in this copy: lanes (`HARNESS_LANE`, `HARNESS_CPUSET`, `HARNESS_CLIENT_CPUSET`, `HARNESS_EXTRA_ARGS` for server options), lane,
  container and cpuset recorded in `env.txt`.
