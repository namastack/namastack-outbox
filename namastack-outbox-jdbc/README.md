# JDBC query structure

The record repository executes named queries through `JdbcOutboxRecordQueries`. It has no database
type detection or dialect switch. Spring auto-configuration and `JdbcOutboxPersistenceFactory` supply
a query provider; the repository resolves it once, lazily. Creating persistence therefore does not
open a connection, and the resolved table name stays fixed for its lifetime.

```text
query/
├── JdbcOutboxRecordQueries.kt          Contract, including ordering and retry barriers
├── DefaultJdbcOutboxRecordQueries.kt   Shared SQL; each statement can be overridden
├── JdbcOutboxRecordQueriesFactory.kt   Database type → query implementation
├── PostgresJdbcOutboxRecordQueries.kt  PostgreSQL probes and bulk loading
├── MySqlJdbcOutboxRecordQueries.kt     MySQL
├── MariaDbJdbcOutboxRecordQueries.kt   MariaDB, independently extensible
├── SqlServerJdbcOutboxRecordQueries.kt SQL Server
├── OracleJdbcOutboxRecordQueries.kt    Oracle
└── H2JdbcOutboxRecordQueries.kt        H2
```

## Changing a query

Change common SQL in `DefaultJdbcOutboxRecordQueries`. For a database-specific improvement, override
only the affected property in that database's class. All other statements remain inherited. The
repository continues to use the same query name, for example `queries.findStrictRecordKeys`.

Each dialect provides its SQL row-limit clause. JDBC row limits remain applied as well, including
for the portable fallback used for unsupported database products. PostgreSQL can supply its bounded
predecessor source through the base constructor; that keeps its planner-specific SQL in its own file
without invoking overridden methods during base-class initialization.

MySQL and MariaDB currently share the same SQL behavior but have separate classes and factory entries,
so either can evolve without changing the other. The SQL Server grouped non-strict query and Oracle
UTC ordering/optimizer hint remain confined to their respective classes.

To add a database, add its `JdbcDatabaseType`/schema support, one query class, a factory entry, and
integration coverage. No database condition needs to be added to the record repository.

## Correctness contract

Every dialect must preserve:

- Record order within a key: `created_at`, then `id`.
- Strict-mode barriers from every earlier incomplete record, including failed records and future retries.
- Non-strict mode's ability to select a later due record.
- Future retries in bulk-loaded pages; workers decide whether they are eligible.
- The resolved schema/table namespace, SQL parameter names, and bounded cleanup reads.

`AbstractJdbcBulkRecordLoadingTest` checks these behaviors across the supported JDBC databases.
`JdbcOutboxRecordQueriesTest` checks dialect selection, SQL structure, and lazy metadata resolution.
Completion batching has separate classes and tests; it does not decide which read queries to use.

## Running the tests

Run from the repository root with a JDK (the local verification used Java 21) and Docker running.
No database installation, Compose environment, local credentials, or manually configured ports are
required. H2 runs in memory; Testcontainers starts the other databases, assigns ports dynamically,
and stops its containers afterward. Images are downloaded on the first run.

```bash
# Complete JDBC suite: unit tests and integration tests for every supported database
./gradlew :namastack-outbox-jdbc:test

# PostgreSQL-specific integration and completion-updater tests
./gradlew :namastack-outbox-jdbc:test --tests '*Postgres*'

# Fast H2 integration checks without Docker
./gradlew :namastack-outbox-jdbc:test --tests '*H2IntegrationTest'

# Only the shared bulk-loading/ordering contract, on every supported database
./gradlew :namastack-outbox-jdbc:test --tests '*JdbcBulkRecordLoading*'
```

Use the complete module suite before committing a query or completion change. Filtering only
`*IntegrationTest` would miss important failure/commit tests whose names end in `Test`.

Reports are written to `namastack-outbox-jdbc/build/reports/tests/test/index.html`; machine-readable
results are in `namastack-outbox-jdbc/build/test-results/test/`. The existing Gradle CI workflow
already includes this suite through the root `test`/`build` tasks. JDBC integration tests live in this
module alongside unit tests; there is no separate `integrationTest` task. The repository-wide
`namastack-outbox-integration-tests` module covers additional application-level scenarios.

## Test responsibilities

| Suite | Coverage |
| --- | --- |
| `AbstractJdbcBulkRecordLoadingTest` + six database subclasses | Same ordering, eligibility, lazy decoding, key-boundary and cleanup contract on H2, PostgreSQL, MySQL, MariaDB, Oracle and SQL Server |
| `AbstractJdbcCompletionBatchingTest` + six database subclasses | Portable batching, concurrent completions, commit visibility before advancing a key, application rollback, and missing records |
| `JdbcCompletionBulkPostgresIntegrationTest` | Runs the same completion contract with PostgreSQL multi-row updates enabled |
| `JdbcOutboxCompletionBatcherTest` | Controlled commit blocking/failure, driver fallback, poison records, interruption and shutdown |
| `PostgresJdbcOutboxBulkCompletionUpdaterTest` | Native update results, timestamp precision/timezones, nulls, duplicate ids and rollback/commit failures |
| `JdbcPostgresProcessingIndexMigrationTest` | Fresh shipped indexes and repeatable staged migration without changing stored records |
| `schema/` and `scheduling/` | Shipped schema initialization and concurrent transactional scheduling across all six databases |
| Factory/configuration tests | Dialect selection, namespaces, lazy metadata detection, default configuration and routing datasource behavior |

The database subclasses contain only container/startup configuration; shared behavior belongs in the
abstract suites. The shared database suites use the shipped schema resources. Spring test contexts
are closed after each shared-suite subclass, so cached connection pools do not outlive their database
container.

For a new dialect, add its subclass to the shared suites and override a test only when a documented
database difference requires it. A query optimization must continue passing the same ordering and
retry-barrier tests.

The separate [performance-test module](../namastack-outbox-performance-test/README.md) measures
throughput with Docker Compose and an empty handler. It is run explicitly and is not part of CI's
normal correctness suite. Integration tests verify behavior; the load test measures capacity.
