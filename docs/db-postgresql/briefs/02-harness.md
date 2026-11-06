# Brief 02 — Benchmark harness

You are a sub-agent. Inputs: `docs/db-postgresql/01-usage-map.md`, `docs/db-postgresql/01-usage-map-v1-delta.md`,
`docs/db-postgresql/02-scenarios.md`. Output: `docs/db-postgresql/harness/` with a `README.md` explaining usage.

## Isolation rule

Never open, list, grep, glob or summarize anything under `docs/db/` (this includes `docs/db/**` in any
worktree). Exclude it from every search (`grep --exclude-dir=db`, `git diff ... -- . ':!docs'`). Do not
reuse any harness or script from there. If you accidentally read something from `docs/db/`, stop and
report it.

## Requirements

1. **Databases** in Docker with fixed CPU and memory limits, same images as the integration tests:
   `postgres:18-alpine`, `mysql:9.2`, `mariadb:12`, `mcr.microsoft.com/mssql/server:2022-latest`,
   `gvenzl/oracle-xe:18-slim-faststart`; H2 in-process. One script to start/stop each, with default DB
   config (record any setting you change).
2. **Schema per variant.** Load DDL from the variant's worktree
   (`namastack-outbox-jdbc/src/main/resources/schema/<dialect>/outbox-tables.sql`), plus optional
   experiment DDL overrides (`--ddl <file>`).
3. **Data generator** per scenario in `02-scenarios.md`, deterministic (seeded), identical across
   variants, using the real partition hashing (`PartitionHasher`) so partition values are realistic.
   Fast bulk load (`COPY`, multi-row insert, etc.), then statistics refresh (`ANALYZE` or equivalent).
   Snapshot/restore support so variants run against the same data.
4. **Query catalog** per variant and dialect, extracted from code (the SQL the repository really
   sends), with realistic parameter sets per scenario (owned partitions, record keys, batch sizes,
   cutoffs). Keep it in files, one per query id, so experiments can override a single query.
5. **SQL-level runner**: warmup, ≥20 measured repetitions (configurable), median, p95, min/max, rows
   returned; plans with actual statistics (Postgres `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, MySQL/MariaDB
   `EXPLAIN ANALYZE`/`ANALYZE FORMAT=JSON`, SQL Server actual plan XML / `SET STATISTICS IO, TIME`,
   Oracle `DBMS_XPLAN.DISPLAY_CURSOR` with `gather_plan_statistics`, H2 `EXPLAIN ANALYZE`). Destructive
   queries (updates, deletes) run in a rolled-back transaction or against restored data.
6. **Repository-level runner** (Kotlin, built against the variant worktree, e.g. a test source set or a
   small Gradle project using `mavenLocal()` after `publishToMavenLocal` from that worktree) that calls
   the real `JdbcOutboxRecordRepository` and `JpaOutboxRecordRepository` on PostgreSQL, measures each
   method, and logs Hibernate SQL. Required for JPA; also run for JDBC/PostgreSQL to compare with the
   SQL-level numbers.
7. **Write cost**: insert latency/throughput, status update latency, table and index sizes, index count.
8. **Concurrency**: N concurrent pollers (owning disjoint partitions) doing the processing loop
   (find keys → load pages → update state) while a producer inserts; report throughput and latency.
9. **Equivalence check**: for two variants on identical data, compare results of every read query
   (rows, order where order is part of the contract, counts) and the effect of every write/delete.
10. **Output format**: `docs/db-postgresql/results/<variant>/<dialect>/<scenario>/` with `timings.csv`
    (query_id, median_ms, p95_ms, min_ms, max_ms, rows, reps), `plans/<query_id>.*`, `env.txt`
    (images, limits, settings, git sha). Provide a compare script that diffs two result dirs into a
    markdown table with relative change.

Validate the harness end-to-end on PostgreSQL scenario S1 for `main`, and on one other dialect.
Return a summary of ≤300 words: how to run, what was validated, known limits.
