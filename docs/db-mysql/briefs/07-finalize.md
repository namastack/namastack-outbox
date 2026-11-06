# Brief 07 — Build the final MySQL candidate in code

You are a sub-agent (Sonnet). The orchestrator gives you: the final set (DDL file, query overlays, experiment reports that justify
each item), the base (`../outbox-wt-my-opt`, the branch tip pinned in `STATE.md`), and the worktree to create
(`../outbox-wt-my-final`, local branch `exp/my-final`).

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md` (pasted into your prompt). You may not read the reference file.

## Task

1. **Code.** Apply the final set to the branch code, MySQL only:
   - DDL: `namastack-outbox-jdbc/src/main/resources/schema/mysql/outbox-tables.sql`.
   - SQL: overrides in `namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/query/MySqlJdbcOutboxRecordQueries.kt`
     (on top of `DefaultJdbcOutboxRecordQueries`; do not change the default class, MariaDB, other dialects or JPA unless the
     orchestrator says so). Only P1/P2 statements change; P3 statements stay as they are on the branch. KDoc on every override explaining *what* the statement does for the MySQL planner (CLAUDE.md
     conventions: current behaviour, `@author`/`@since` as in the file, no comparisons with earlier versions).
   - Other MySQL copies of the schema, if any exist in the branch (examples, test resources): find them with
     `git grep -l -i mysql -- . ':!docs'` and keep them consistent.
2. **Tests.** In the worktree, every Gradle command with `--init-script <primary tree>/docs/db-mysql/harness/gradle/jdk25.init.gradle.kts`
   (the script switches the Java 17 toolchain to 25, bytecode stays 17; harmless although JDK 17 also exists on this host):
   `./gradlew :namastack-outbox-jdbc:test` (includes the MySQL Testcontainers tests, e.g. `JdbcOutboxRecordRepositoryMySqlIntegrationTest`,
   `JdbcSchemaInitializationMySqlIntegrationTest`, `JdbcConcurrentSchedulingMySqlIntegrationTest`; list the ones that exist),
   `./gradlew :namastack-outbox-integration-tests:test --tests "*MySql*"`, `./gradlew ktlintCheck`. These are the project's own
   regression tests and run unchanged (they are not study measurements; the "no Priority 3" rule of this study concerns timings,
   plans and equivalence runs). Do not run Gradle while a timed harness step runs (ask the orchestrator). Add or adapt tests for new statements (query factory test, schema test expecting the index list).
   Report failures with output; never weaken a test.
3. **Docs.** Update `namastack-outbox-docs/docs/reference/upgrading-to-1-12.md` (section "MySQL and MariaDB": split it if MySQL's
   indexes now differ from MariaDB's; steps to drop the old and create the new indexes, written as steps) and
   `namastack-outbox-docs/docs/reference/database.md` where it lists MySQL indexes. Follow the CLAUDE.md documentation rules.
4. **Catalog.** `harness/bin/extract-catalog.sh my-final ../outbox-wt-my-final` and diff `catalog/my-final/mysql/` against the
   final overlays: the SQL the code sends must equal the measured SQL (whitespace aside). Report every difference.
5. **MariaDB compatibility smoke.** A MariaDB server reached through MySQL Connector/J gets the MySQL statements and DDL. Start
   `mariadb:12` in lane 1 (`bin/db.sh start mariadb`, only while no timed step runs), apply the final MySQL DDL and run every changed
   MySQL statement once on S1 (`bench equiv --dialect mariadb --groups HOT,WRITE,MON` with the `my-final` MySQL catalog as overlay
   against `my-opt`, or an equivalent check). Report parse errors and result differences only; no timings. Remove the container
   afterwards.
6. Do not commit to or push any existing branch. Commit on `exp/my-final` only (message prefix as the orchestrator says), so the
   orchestrator can show the diff.

Return a summary of ≤300 words: files changed, test results, catalog check, open issues.
