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
