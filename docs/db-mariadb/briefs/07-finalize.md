# Brief 07 — Build the final MariaDB candidate in code

You are a sub-agent (Sonnet). The orchestrator gives you: the final set (DDL file, query overlays, experiment reports that justify
each item), the base (`../outbox-wt-mdb-opt`, the branch tip pinned in `STATE.md`), and the worktree to create
(`../outbox-wt-mdb-final`, local branch `exp/mdb-final`).

## Isolation rule

See `docs/db-mariadb/briefs/00-isolation-rule.md` (pasted into your prompt). You may not read the reference file.

## Task

1. **Code.** Apply the final set to the branch code, MariaDB only:
   - DDL: `namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql`.
   - SQL: overrides in `namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/query/MariaDbJdbcOutboxRecordQueries.kt`
     (on top of `DefaultJdbcOutboxRecordQueries`; do not change the default class, MySQL, other dialects or JPA unless the
     orchestrator says so). KDoc on every override explaining *what* the statement does for the MariaDB planner (CLAUDE.md
     conventions: current behaviour, `@author`/`@since` as in the file, no comparisons with earlier versions).
   - Other MariaDB copies of the schema, if any exist in the branch (examples, test resources): find them with
     `git grep -l -i mariadb -- . ':!docs'` and keep them consistent.
2. **Tests.** In the worktree: `./gradlew :namastack-outbox-jdbc:test` (includes the MariaDB Testcontainers tests
   `JdbcOutboxRecordRepositoryMariaDbIntegrationTest`, `JdbcSchemaInitializationMariaDbIntegrationTest`,
   `JdbcConcurrentSchedulingMariaDbIntegrationTest`), `./gradlew :namastack-outbox-integration-tests:test --tests "*MariaDb*"`,
   `./gradlew ktlintCheck`. Add or adapt tests for new statements (query factory test, schema test expecting the index list).
   Report failures with output; never weaken a test.
3. **Docs.** Update `namastack-outbox-docs/docs/reference/upgrading-to-1-12.md` (section "MySQL and MariaDB": split it if MariaDB's
   indexes now differ from MySQL's; steps to drop the old and create the new indexes, written as steps) and
   `namastack-outbox-docs/docs/reference/database.md` where it lists MariaDB indexes. Follow the CLAUDE.md documentation rules.
4. **Catalog.** `harness/bin/extract-catalog.sh mdb-final ../outbox-wt-mdb-final` and diff `catalog/mdb-final/mariadb/` against the
   final overlays: the SQL the code sends must equal the measured SQL (whitespace aside). Report every difference.
5. Do not commit to or push any existing branch. Commit on `exp/mdb-final` only (message prefix as the orchestrator says), so the
   orchestrator can show the diff.

Return a summary of ≤300 words: files changed, test results, catalog check, open issues.
