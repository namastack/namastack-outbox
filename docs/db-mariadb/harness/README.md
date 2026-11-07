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

## MariaDB port

Changes made for the MariaDB study (all in `sql/` and `repo/`, plus `bin/`):

- **setMaxRows fidelity.** The SQL runner uses MariaDB Connector/J 3.5.10 (`jdbc:mariadb:`, client-side prepared statements) for every
  variant and calls the real `PreparedStatement.setMaxRows(n)` for catalog entries whose `maxrows` is not `none`. Against MariaDB >= 10.3 the
  driver rewrites this to `SET STATEMENT SQL_SELECT_LIMIT=n FOR <sql>` (server-side limit). Checked with the server's `general_log`
  (`results/validation/VALIDATION.md`): the server receives exactly that text. **Plans use the same limit**: for a query with `maxrows` != none the
  capture is `SET STATEMENT SQL_SELECT_LIMIT=n FOR ANALYZE FORMAT=JSON <sql>` (`EXPLAIN FORMAT=JSON` for capped queries), because the limit changes the
  chosen plan (e.g. `find_completed_before` on `main`: full scan without the limit, index range with it). `plans/<id>.summary.txt` states the limit.
- **Plans.** `ANALYZE FORMAT=JSON` for every query with the first parameter set (writes in a rolled-back transaction); `plans/<id>.json` plus
  `plans/<id>.summary.txt` (per table: access type, key, used key parts, loops, estimated/actual rows, rows examined, index condition, attached
  condition, covering index, rowid filter; then filesort, temporary table, duplicates removal, strategy keywords such as `dependent-subquery`,
  `subquery-cache`, `materialization`; rows examined vs returned). "examined" uses `r_index_rows` when MariaDB reports it. Very slow queries (> 30 s)
  and queries stopped by the per-query limit get a non-executing `EXPLAIN FORMAT=JSON`.
- **Rows examined.** `handlers.csv` (`query_id,Handler_read_key,_next,_prev,_first,_rnd,_rnd_next,_last,bp_read_requests,bp_reads`): session
  `Handler_read%` deltas of one extra execution after the timed reps (never inside the timed section); the cost of two consecutive `SHOW STATUS` calls is
  subtracted. `bp_*` are deltas of the global `Innodb_buffer_pool_read_requests/reads` (the lane's server has no other client).
- **Statistics and warm-up.** `prepare` ends with plain `ANALYZE TABLE outbox_record` (persistent InnoDB statistics; with `use_stat_tables=
  PREFERABLY_FOR_QUERIES` this creates no engine-independent statistics: `env.txt` shows 0 rows in `mysql.table_stats/column_stats/index_stats`).
  Before the first timed query `bench` waits for `Innodb_history_list_length` < 1000 (max 120 s) and records the value, then primes the buffer pool
  identically for every variant: one `SELECT COUNT(*) ... FORCE INDEX (i)` per index (smallest first) until 80 % of `innodb_buffer_pool_size` is
  covered (`--no-prime` disables); the 5 warm-up reps per query follow. `env.txt` records the server settings (`innodb_stats_persistent*`, `use_stat_tables`,
  `optimizer_use_condition_selectivity`, `eq_range_index_dive_limit`, `optimizer_switch`, buffer pool, flush settings, `log_bin`, `explicit_defaults_for_timestamp`,
  `character_set_server`, `collation_server`, `sql_mode`), the actual charset/collation of `id` and `record_key` from `information_schema.COLUMNS`, history list
  length, priming note, and the sizes (per-index sizes from `mysql.innodb_index_stats`, tablespace file size from `INNODB_SYS_TABLESPACES`).
- **Write cost (InnoDB).** `writecost` phases `insert_1thread`, `insert_4threads`, `upd_complete_committed`, `upd_retry_committed`, `upd_fail_committed`,
  `delete_by_id_committed` (deletes the records completed in the first update phase). Per phase and per write: global deltas of `Innodb_os_log_written` (redo bytes),
  `Innodb_pages_written`, `Innodb_data_written`, `Innodb_buffer_pool_read_requests`, `Innodb_buffer_pool_reads`, row-lock waits/time, deadlocks; latency p50/p95
  and throughput. Before and after each phase all dirty pages are flushed (`SET GLOBAL innodb_max_dirty_pages_pct=0`, wait until the dirty count is <= 5 or stable,
  settings restored), so pages/data written count the pages the phase dirtied (an idle server otherwise defers them); latencies are measured before that flush.
  Table and per-index sizes before/after come from a plain `ANALYZE TABLE` + `mysql.innodb_index_stats`, plus the tablespace file size and the index count.
  The counters are GLOBAL: run one lane per server and nothing else against it.
- **S9 / S10.** `churn` records sizes, `Innodb_history_list_length` and global counter deltas before/after (no manual `ANALYZE`/`OPTIMIZE`); `run-cells.sh churn:*:S9`
  then runs the P1/P2 bench on the churned table under the same label. `concurrency` works on MariaDB (disjoint partitions per poller, `--rate 500` or `0`) and adds
  history list length and `Innodb_row_lock_waits`, `Innodb_deadlocks`, `Innodb_row_lock_time`, redo/pages deltas to `concurrency-<tag>.txt`.
- **Equivalence.** `keys_strict`/`keys_nonstrict`: a differing limited list is `WARN` only when the full key sets are equal AND both limited lists are sorted by
  `MIN(created_at)` of the key's due rows AND their created_at sequences are identical (so the difference is only a permutation inside created_at ties, e.g. `main` orders by
  `MIN(created_at)` only, the branch by `(created_at, id)`); otherwise `FAIL`. For `find_*`/`ids_*` the `ORDER BY` column is compared the same way (looked up by id for `ids_*`). Self-tests: an
  override with a deliberately wrong order gives `FAIL`; an override that permutes the tie gives the verified `WARN`.
- **Repository-level runner.** `bin/repo-bench <v> bench --engine jdbc --dialect mariadb --worktree W --scenario S1`: Hikari (4 connections) with MariaDB Connector/J on the lane's
  port, the worktree's real `JdbcOutboxPersistenceFactory.create(...)`. `repo-jdbc/sql.txt` starts with the product the datasource reports and the dialect class the repository resolved
  (`io.namastack.outbox.query.MariaDbJdbcOutboxRecordQueries` on the branch; `rowLimitClause` on `main`), then every statement with the `setMaxRows` it was prepared with. The runner is compiled
  against the `mdb-main` interface; methods whose signature differs between variants (the `status` argument) are called by reflection (`callFlex`).
- **Per-query limit.** `--query-limit-s` / `--limit-from` work with the MariaDB driver (`Statement.cancel()` = `KILL QUERY`); `bench limitcheck --dialect mariadb --query-limit-s 3` stops a long query after 3.2 s.
- **Drivers.** `bin/run-cells.sh` (resumable single-lane driver, see its header) and `bin/noise.py <tree1> <tree2>` (any two result trees, per group: p90/max relative spread; p90/max absolute difference below 0.3 ms).
- **Fixes during run `cmp`.** (1) `bin/run-cells.sh`: `scen_of`/`extra_of` now map `S16-<S>` (to scenario `<S>`, `--owned 0,16,...,240`) and `S10-<S>-p<n>[-unthr]` (to scenario `<S>`); before, these labels resolved to scenario `S16`/`S10`. (2) `bench churn --retention-max-s N` (default 0 = unlimited) stops the retention-delete phase after N seconds and records `retention_capped_at_s` in `churn.txt`; needed because the `mdb-main` retention select (`ids_completed_before`) takes about 2 min per 1,000-row batch on the S9 table (500 batches = 16 h). The `cmp` run used `--retention-max-s 2700` for both variants. The harness was rebuilt (`:sql:installDist`) once before the affected cells ran; no earlier result was produced with a different binary.
- **Round 1a: `bench --pages-keys-file F`.** `pages` uses the keys listed in `F` (one per line) instead of the `keys_strict` result, so `pages` is measured when `keys_strict` is stopped at the per-query limit (S4 full ownership). Without the option nothing changes.
- **Round 1b (M5): code-class `pages` forms.** `Params.kt` adds `:recordKeysJson` (JSON array string of the page keys) and `:recordKeysRows` (a `RowList`, expanded by `NamedSql.expand` to `(?),(?),...`, for `VALUES :recordKeysRows`). `NamedSql.perKeyUnion` rebuilds a statement written for `:recordKey1`, `:recordKey2` (`(SELECT ... :recordKey1 ...) UNION ALL (SELECT ... :recordKey2 ...)`) with one arm per page key, and `:recordKeyN` binds the N-th key. Nothing changes for statements that use none of these names. Driver: `experiments/r1b/run-m5.sh` (labels S8-S5, S5, S4-b100 with `--pages-keys-file`).
- **Round 2: no harness code change.** Drivers `experiments/r2/run-r2.sh` (lane 1; checks that the snapshot marker's `ids=uuidv7` matches the run before every step; `HARNESS_ID_VERSION=7` exported for gen, prepare, bench, writecost and concurrency of the v7 runs) and `experiments/r2/run-equiv.sh` (lane 2, via `run-cells.sh`).
- **Confirm stage: `bench plantime`.** New command (`sql/.../Plantime.kt`, registered in `Main.kt`): for the queries given by `--only` (default `keys_strict, keys_nonstrict, oldest_created, oldest_due_strict, oldest_due_nonstrict`) it times `EXPLAIN FORMAT=JSON <literal statement>` through the bench JDBC path (same `SET STATEMENT SQL_SELECT_LIMIT` prefix as the plan capture, 3 warm-up + `--reps` executions, default 20), writes `plantime.csv` (`query_id,median_ms,p95_ms,min_ms,reps`) and one `optimizer_trace` per query (`trace/<id>.json`, from `information_schema.OPTIMIZER_TRACE`). Nothing is executed or modified. Same options as `bench` (`--variant/--ddl/--queries/--owned/--label`); run `prepare` first. Drivers: `experiments/c/run-c.sh pass1|pass2|iso` (resumable, wraps `run-cells.sh` per variant and label) and `experiments/c/run-equiv.sh` (lane 2). Run-cells needed no change.
- **Catalog extractor.** The `oldest_created` `maxrows` of the branch is `none` (the code at `2a2663de` calls no `setMaxRows`).

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

- Validated on 2026-11-05 (JDK 25, lanes 1 and 2, `mariadb:12` = 12.3.3) on S1 only; evidence in `../results/validation/VALIDATION.md`. Larger scenarios
  (S2+) are validated by the first real runs; `gen` for MariaDB is JDBC-batch based (1M rows take several minutes).
- Inherited from the PostgreSQL study: the SQL-level numbers use one JDBC connection (no pool, no Spring); network/driver round trips are included.
  Concurrency pollers use the catalog SQL (exactly what the variant sends) but not the scheduler's thread pool.
- `writecost`'s update phases use three disjoint slices of due ids (`min(n, due/3)`); S1 has only 150 due records, so use `--n 120` or less there.
- The repository-level runner on MariaDB is JDBC only; JPA stays PostgreSQL-only.

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
- M5 follow-up (`experiments/m5-s10.md`): `concurrency` pollers replace `recordKeys` with the keys of the keys query for every `pages` call; `Ctx.bindKeyForms` (`Params.kt`) now re-derives `recordKeysJson` / `recordKeysRows` from them, so `JSON_TABLE` / `VALUES` forms of `pages` run with the poller's keys (before the change those two parameters held the static sample).
