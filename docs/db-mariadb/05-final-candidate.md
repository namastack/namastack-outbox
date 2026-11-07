# 05 Final candidate

Worktree `/home/azamojski/outbox-wt-mdb-final`, branch `exp/mdb-final`, base 2a2663de, commit `76c51fdb`.

## Files changed
- `namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql`: `idx_outbox_record_status_completed_partition` replaced by `idx_outbox_record_status_partition_created (status, partition_no, created_at)`. Diff against `experiments/r2/spc/ddl.sql` showed this was the only difference. No other MariaDB copy of the schema exists (examples and MySQL/H2 files untouched).
- `MariaDbJdbcOutboxRecordQueries.kt`: overrides of `findStrictRecordKeys`, `findNonStrictRecordKeys`, `oldestCreatedAtByPartitionsStatus`, `oldestDueNonStrict` (forms-a), with KDoc.
- `JdbcOutboxRecordRepository.kt` (outside the MariaDB class, see below).
- Tests: new `MariaDbJdbcOutboxRecordQueriesTest`; `JdbcSchemaInitializationMariaDbIntegrationTest` now asserts the exact index list.
- Docs: `upgrading-to-1-12.md` (MySQL and MariaDB split; MariaDB drops/creates per the new index; benchmark note now names MariaDB), `database.md` (MariaDB fourth index).

## Change outside the MariaDB class
`findOldestCreatedAtByPartitions` now binds `.param("limit", 1)` and `.withMaxRows(1)`, as `findOldestDueNextRetryAtByPartitions` does. Needed because the MariaDB statement is now `ORDER BY ... LIMIT :limit`. Other dialects' SQL is byte-identical (verified by catalog); their MIN statement has no `:limit`, so the extra param is unused, and maxRows 1 is harmless for a one-row aggregate. Zero rows map to null through `list().firstOrNull()`. The ORDER BY ... LIMIT 1 result equals MIN(created_at) (same predicate, ascending order; NULL created_at impossible, column NOT NULL).

## Tests (JDK 25 init script)
- `:namastack-outbox-jdbc:test`: 537 tests, 0 failures, 0 errors, 0 skipped (includes MariaDB repository, schema, concurrency tests with Testcontainers).
- `:namastack-outbox-integration-tests:test --tests "*MariaDb*"`: 2 tests (JpaSchemaValidationMariaDb), 0 failures.
- `ktlintCheck`: passes.

## Catalog check (`extract-catalog.sh mdb-final`)
- keys_strict, keys_nonstrict, oldest_created, oldest_due_nonstrict: SQL equals the overlays (whitespace aside). Only header difference: extractor writes `maxrows: none` for oldest_created; the code now does limit 1, matching the overlay's `one`.
- Every other id on mariadb, mysql, postgres, sqlserver, oracle, h2: identical to mdb-opt apart from the `-- source` variant label.

## Open issues
- Not measured at repository level; the JPA module's MariaDB DDL (entity index annotations) was not changed, as instructed.
