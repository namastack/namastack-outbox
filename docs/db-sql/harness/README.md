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
  sql/  repo/            Gradle sub-projects (Kotlin, JDK 17 toolchain), built on first use
../results/<variant>/<dialect>/<scenario>/   timings.csv  plans/  env.txt  (+ writecost.csv, concurrency-*.txt, churn.txt, repo-jdbc/, repo-jpa/)
../results/equiv/<dialect>/<scenario>/<a>-vs-<b>.md
```

All commands are run from anywhere; the scripts `cd` into `harness/`. Paths below are relative to `harness/`.
Needs: Docker, JDK 17+ (build uses toolchain 17; runs fine on 25), network for the first Gradle build.
The harness never modifies a worktree (only its `build/` directories change when jars are built).

## Quick start (PostgreSQL, S1, variant `main`)

```bash
bin/db.sh start postgres                                   # one dialect at a time (starting one stops the others)
bin/variant-build.sh main /home/zamo/git/outbox-wt-main    # jars for the repository-level runner
bin/extract-catalog.sh main /home/zamo/git/outbox-wt-main  # catalog/main/*   (repeat for v1 / experiments)
bin/bench gen --dialect postgres --scenario S1             # deterministic snapshot into schema "snap" (seed 42)
bin/bench bench --dialect postgres --variant main --worktree /home/zamo/git/outbox-wt-main --scenario S1 --prepare
bin/repo-bench main bench --engine jdbc --dialect postgres --worktree /home/zamo/git/outbox-wt-main --scenario S1
bin/repo-bench main bench --engine jpa  --dialect postgres --worktree /home/zamo/git/outbox-wt-main --scenario S1
bin/compare.py ../results/main/postgres/S1 ../results/v1/postgres/S1          # add --sub repo-jdbc for repository level
bin/bench equiv --dialect postgres --a main --b v1 --worktree-a /home/zamo/git/outbox-wt-main --worktree-b /home/zamo/git/outbox-wt-v1 --scenario S1
bin/cleanup.sh                                             # stop and remove containers (data volumes survive unless --wipe)
```

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
  Experiments with other jars: `bin/extract-catalog.sh exp-3 /home/zamo/git/outbox-wt-exp-3`; the repository-level runner
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

- Validated end to end only for PostgreSQL (S1: SQL level main and v1, repository level JDBC and JPA, equivalence main vs v1, write cost,
  concurrency, churn on small data) and MySQL (S1 SQL level); the MariaDB, SQL Server, Oracle and H2 paths (schema reset, DDL splitting,
  load, plan capture, size reports) are implemented but not exercised.
- The SQL-level numbers use one JDBC connection (no pool, no Spring); the repository level uses Hikari. Network/driver round trips are included.
- Plans are not captured for the rolled-back statement's commit; PostgreSQL write timings exclude commit (use `writecost` for committed latency).
- The repository-level runner is PostgreSQL only; JPA uses default Hibernate settings (no batching, no IN-clause padding).
- Row-limit behavior of `setMaxRows` is the driver's; the harness does not force anything.
- `gen` for MySQL/MariaDB/Oracle/SQL Server is batch-insert based: 1M rows takes several minutes there.
- Concurrency pollers use the catalog SQL (so they run exactly what the variant sends) but not the scheduler's thread pool; one poller handles
  records of a key sequentially, keys one after another.

## Changes

- Added `bin/matrix-pg.sh` (PostgreSQL matrix driver: per scenario one snapshot, run 1 main->v1 then container restart and run 2 `-r2` v1->main, plus repo-level, writecost, churn, concurrency and equivalence for S1/S2/S4/S5), `bin/s2l-pg.sh` (S2L), `bin/noise.py` (run 1 vs run 2 spread report) and `bin/summary.py` (SUMMARY skeleton generator). No change to the runners.
- S3 equivalence reports `delete_by_ids` ERROR (`IN ()` syntax error): S3 has no COMPLETED rows, so the sampled id list is empty. This is a harness artefact, identical for both variants, not a variant difference.
- The `S10` producer at `--rate 500` achieves only ~320-480 inserted rec/s (one producer thread); the target rate is not reached.

- **Orchestrator fix (after first PostgreSQL matrix):** PostgreSQL `prepare` now runs `VACUUM (FREEZE, ANALYZE) outbox_record`
  instead of `ANALYZE`. Without it the visibility map was empty and hint bits unset after the bulk `INSERT ... SELECT`, so
  index-only scans hit the heap and insert-triggered autovacuum ran at a random moment during benchmarks (run-to-run spreads up to 24x).
  A steady-state production table has a vacuumed completed tail. S9 (`churn`) still runs no manual VACUUM after its churn phase.
  The first PostgreSQL matrix (unvacuumed) is kept under `results/_unvacuumed/` for reference.
- **Second PostgreSQL matrix:** the `pg_stat_user_tables` line in `env.txt` now lists `n_live_tup, n_dead_tup, last_vacuum, last_autovacuum, last_autoanalyze, vacuum_count, autovacuum_count` (was without `last_vacuum`/counts) so the manual `prepare` VACUUM and any autovacuum during a bench are visible. Old logs moved to `results/logs/prev/`.
- **Equivalence sampling:** the single-key/id parameters are sampled with plain SQL without `ORDER BY`; after the `prepare` VACUUM the two equivalence schemas (different index sets) can return different sample rows, so `upd_*`, `find_incomplete_by_key`, `count_partition_single`, `delete_by_key_status` compared different rows and reported FAIL (S1, S4, S5 of the second matrix). `HARNESS_SAMPLE_ORDERED=1` appends `ORDER BY 1` to every sample query; the equivalence reruns use it. The timing matrix ran without it: on S5 `main` and `v1` sampled different keys (1999 vs 2000 rows for the single-key queries), single-key rows of other scenarios have 1 row either way.
- Added `bin/summary2.py` (compact per-variant SUMMARY: slowest queries with plan shape, write cost, S10, S9, repo level) and extended `bin/noise.py` with the per-group p90 of the absolute run difference for queries below 0.3 ms.
- **Orchestrator:** ordered sampling (`ORDER BY 1` on parameter samples) is now the default; `HARNESS_SAMPLE_ORDERED=0` disables it.
  The vacuumed PostgreSQL matrix of phases 3/4 ran with unordered sampling (single-key/id parameters may differ between variants there).
- **Slow-query cap (orchestrator, cross-dialect matrix):** if the first execution of a query takes > 30 s, that single execution is the measurement (`reps=1`, note `very slow (>30s): 1 execution`) and it is not repeated; its plan is a non-executing one (MySQL `EXPLAIN FORMAT=TREE`, MariaDB `EXPLAIN FORMAT=JSON`, SQL Server `SET SHOWPLAN_XML ON` estimated plan, Oracle `EXPLAIN PLAN` + `DBMS_XPLAN.DISPLAY`; PostgreSQL/H2 keep EXPLAIN ANALYZE). The 2-30 s rule is unchanged. New option `bench bench --groups HOT,WRITE,MON` restricts a run to the given catalog groups (used for the `S8-*` labels, which skip RET and ADMIN). New driver `bin/matrix-sql.sh` (all non-PostgreSQL dialects; `SKIPGEN=1` reuses the existing snapshot; finished bench results are skipped unless `REDO=1`).
- `bin/matrix-sql.sh`: equivalence runs on S1 only unless `EQUIV_BIG=1` (it builds two extra 1M-row schemas next to the snapshot and run schema; MySQL S2 equivalence filled the disk: `The table 'outbox_record' is full`).
- **`bin/bench` rebuilds** the installed distribution when a `sql/src/**/*.kt` file is newer than it (before, it only built when missing, so source changes were silently ignored). The MySQL matrix S1, S2 and S8-S2 ran with the binary built before the slow-query cap and `--groups` (all queries repeated with 1 warmup + 5 reps when > 2 s, S8-S2 with all groups).
- **MariaDB connection:** `sessionVariables=time_zone='+00:00'` (was `%2B00:00`, which MariaDB Connector/J does not decode: `Unknown or incorrect time zone`).
- **Orchestrator:** `CatalogExtractor` also reads variants with the `io.namastack.outbox.query` package
  (`JdbcOutboxRecordQueriesFactory.create`, statements `findStrictRecordKeys`, `findNonStrictRecordKeys`, `findOldestStrictDueRetry`,
  `findOldestDueRetry`), mode `query-package`. Older variants keep working. Verified: the refactored primary tree yields the same SQL as
  `catalog/v1` for all 28 queries × 6 dialects. After editing the extractor, rebuild the runner (`./gradlew :repo:installDist`); `bin/repo-bench`
  only builds it when missing.
