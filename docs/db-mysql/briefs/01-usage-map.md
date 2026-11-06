# Brief 01 — `outbox_record` usage map for MySQL

You are a sub-agent (Sonnet). The orchestrator gives you: the `main` worktree, the branch worktree, the output file.

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md`; the orchestrator also pastes it into your prompt. You may **not** read
`docs/db-mysql/reference/postgres-rationale.md`.

## Task

Read code only (Kotlin/Java sources, DDL, tests). Focus on `OutboxRecordRepository`
(`namastack-outbox-core/src/main/kotlin/io/namastack/outbox/OutboxRecordRepository.kt`), its JDBC implementation and the MySQL
path. JPA and MongoDB only as context (JPA is not changed or measured in this study).

Write one markdown report with:

1. **Table on MySQL.** Columns, types, nullability, character sets/collations (table default vs explicit column collations; the
   MySQL 8.4 server default is `utf8mb4_0900_ai_ci`), indexes and constraints in
   `namastack-outbox-jdbc/src/main/resources/schema/mysql/outbox-tables.sql`, for `main` and for the branch. InnoDB consequences
   stated as general knowledge: clustered PK on `id VARCHAR(255)`, every secondary entry carrying the PK, `TIMESTAMP` second
   precision, index key length limits with utf8mb4 (3072 bytes per index with DYNAMIC rows).
2. **Field semantics and invariants.** Status state machine (who sets NEW / COMPLETED / FAILED and when), `next_retry_at`,
   `failure_count`, `completed_at` (`completed_at IS NULL` ⇔ NEW or FAILED), `created_at` + `id` ordering per `record_key`,
   `partition_no` and `PartitionHasher`, `handler_id`. Small state diagram.
3. **Dialect detection.** How the branch picks the MySQL statements (`JdbcDatabaseType`, `JdbcOutboxRecordQueriesFactory`,
   autoconfiguration): which JDBC product names / drivers map to MySQL and which to MariaDB. In particular: a **MariaDB server
   reached through MySQL Connector/J** (or with `useMysqlMetadata=true`) — does it get the MySQL class and `schema/mysql` DDL? Then
   every MySQL statement this study changes also reaches such MariaDB deployments: list what that implies. Which class does `main`
   use?
4. **Method catalog.** For every repository method: purpose, callers (file:line), trigger and frequency (per poll, per key, per
   record, metrics refresh, retention, admin), configuration that enables it; the **exact SQL sent to MySQL** on `main` and on the
   branch (resolve templates, `LIMIT :limit` vs `setMaxRows`); parameters and result size; transaction/locking; which index of each
   variant can serve it and the likely InnoDB access path. Describe P3 methods briefly (they are not measured in this study).
   **`setMaxRows` on MySQL Connector/J:** read the driver behaviour from its source jar (Gradle cache) or documentation: does it send
   `SET SQL_SELECT_LIMIT=n` before the statement and `SET SQL_SELECT_LIMIT=DEFAULT` after it (extra round trips), does it skip that
   when the SQL already contains `LIMIT`, does it apply with client-side vs server-side prepared statements? Mark it unverified if
   the source is not available; phase 2 verifies it with the server's `general_log`.
5. **Priority grouping (ADR-0005).** P1 scheduling/execution, P2 metrics collector, P3 retention/admin. Per group: methods,
   frequency, why it matters.
6. **Write amplification on InnoDB.** Columns changed by each `updateProcessingState` outcome (complete, retry, fail), `create`,
   `deleteById`; which secondary indexes of each variant must be maintained (delete-mark + insert when a key column changes).
7. **Branch change list.** Every MySQL-relevant difference between `main` and the branch
   (`git diff <main>..<branch> -- . ':!docs'`), each as one independently revertible item `B-1`, `B-2`, … (one index, one rewritten
   statement, one new statement path): description, files, affected query ids (ids from `docs/db-mysql/02-scenarios.md`), and the
   priority of the affected queries (items touching only P3 are listed but marked "P3, not measured"). Describe what the code does,
   not why someone may have chosen it.
8. **Open questions** for the orchestrator.

Return a summary of ≤300 words with the output path.
