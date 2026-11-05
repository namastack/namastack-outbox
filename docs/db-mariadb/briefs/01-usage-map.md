# Brief 01 — `outbox_record` usage map for MariaDB

You are a sub-agent (Sonnet). The orchestrator gives you: the `main` worktree, the branch worktree, the output file.

## Isolation rule

See `docs/db-mariadb/briefs/00-isolation-rule.md`; the orchestrator also pastes it into your prompt. You may **not** read
`docs/db-mariadb/reference/postgres-rationale.md`.

## Task

Read code only (Kotlin/Java sources, DDL, tests). Focus on `OutboxRecordRepository`
(`namastack-outbox-core/src/main/kotlin/io/namastack/outbox/OutboxRecordRepository.kt`), its JDBC implementation and the MariaDB
path. JPA and MongoDB only as context (JPA is not changed or measured in this study).

Write one markdown report with:

1. **Table on MariaDB.** Columns, types, nullability, character sets/collations, indexes and constraints in
   `namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql`, for `main` and for the branch. InnoDB consequences
   stated as general knowledge: clustered PK on `id VARCHAR(255)`, every secondary entry carrying the PK, `TIMESTAMP` second
   precision, index key length limits with utf8mb4.
2. **Field semantics and invariants.** Status state machine (who sets NEW / COMPLETED / FAILED and when), `next_retry_at`,
   `failure_count`, `completed_at` (`completed_at IS NULL` ⇔ NEW or FAILED), `created_at` + `id` ordering per `record_key`,
   `partition_no` and `PartitionHasher`, `handler_id`. Small state diagram.
3. **Dialect detection.** How the branch picks the MariaDB statements (`JdbcDatabaseType`, `JdbcOutboxRecordQueriesFactory`,
   autoconfiguration): which JDBC product names / drivers map to MariaDB and which to MySQL. Can a MariaDB server reached through
   the MySQL driver end up with the MySQL class (or vice versa)? Which class does `main` use?
4. **Method catalog.** For every repository method: purpose, callers (file:line), trigger and frequency (per poll, per key, per
   record, metrics refresh, retention, admin), configuration that enables it; the **exact SQL sent to MariaDB** on `main` and on the
   branch (resolve templates, `LIMIT :limit` vs `setMaxRows`); parameters and result size; transaction/locking; which index of each
   variant can serve it and the likely InnoDB access path. Note how MariaDB Connector/J applies `setMaxRows` (read the driver
   behaviour from its documentation or source jar if available; otherwise mark it as unverified).
5. **Priority grouping (ADR-0005).** P1 scheduling/execution, P2 metrics collector, P3 retention/admin. Per group: methods,
   frequency, why it matters.
6. **Write amplification on InnoDB.** Columns changed by each `updateProcessingState` outcome (complete, retry, fail), `create`,
   `deleteById`; which secondary indexes of each variant must be maintained (delete-mark + insert when a key column changes).
7. **Branch change list.** Every MariaDB-relevant difference between `main` and the branch
   (`git diff <main>..<branch> -- . ':!docs'`), each as one independently revertible item `B-1`, `B-2`, … (one index, one rewritten
   statement, one new statement path): description, files, affected query ids (ids from `docs/db-mariadb/02-scenarios.md`).
   Describe what the code does, not why someone may have chosen it.
8. **Open questions** for the orchestrator.

Return a summary of ≤300 words with the output path.
