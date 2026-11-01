# v1 delta: `outbox_record` SQL, DDL, index and caller changes (main..e0b09b4)

Source: `git diff main..e0b09b4 -- . ':!docs'`, code only. Scope: `OutboxRecordRepository`, JDBC and JPA.
Whitespace in SQL is normalised (`toSingleLine`). `:limit` is a named parameter bound to the batch size (or 1).
MongoDB: `MongoOutboxRecordRepository`, `MongoOutboxRecordEntity` and `mongodb-setup.js` also changed (out of scope, not analysed).

Convention: "main" = before, "v1" = after. Dialects: H2 (h2), PG, MySQL (my), MariaDB (maria), SQL Server (mssql), Oracle (ora).

## Dependency overview

- V1-0 (dialect query infrastructure) is a prerequisite of V1-4..V1-9 in their dialect-specific forms; those items can be reverted individually by pointing the statement back to the main text.
- V1-2 (new queue index) is independent DDL. Query items V1-4, V1-5, V1-7 and V1-6 are written to use V1-1b / V1-2 (the code comments say the outer scan is served by the incomplete-record index and the predecessor check by the record-key index). Reverting the index items does not break correctness, only the access paths.
- V1-1b (id appended to the record-key index) is referenced by the predecessor checks of V1-4, V1-5, V1-7.
- V1-10 (countByStatuses) and V1-11 (metrics) depend on each other: the collector calls the new repository method; reverting V1-10 alone breaks V1-11 (the interface has a default implementation that loops over `countByStatus`, so only the JDBC/JPA overrides could be removed without breaking compilation).
- V1-3 (Postgres statistics) is independent.
- JPA entity index annotations (V1-1/V1-2) mirror the DDL files and must be reverted together with them for JPA users using schema generation.

## V1-0 Dialect-specific query class and database detection (caller change)

- Files: new `namastack-outbox-jdbc/.../JdbcOutboxRecordQueries.kt` (base + `H2/Postgres/MySql/SqlServer/Oracle` subclasses; MySQL class used for MariaDB), `JdbcOutboxRecordRepository.kt` (all SQL strings moved out, `queries` lazy via `databaseTypeResolver`), `JdbcOutboxPersistenceFactory.kt` (new `create(..., databaseType)` overload), `config/JdbcDatabaseType.kt` (`detect(dataSource)`), `config/JdbcOutboxAutoConfiguration.kt` (passes `dataSource`).
- Behaviour: first record query calls `DatabaseMetaData.getDatabaseProductName()` (one connection, once; failure retried); unsupported product -> base (portable) SQL.
- Portable SQL base has no row-limit clause (`withMaxRows` applies it). Unchanged statements (insert, update, updateProcessingState, findByStatus, findByKeyAndStatus, countByStatus, countByPartitionStatus, countByPartitionsStatus, deleteBy*, `oldestDueNextRetryAtWithoutPreviousFailureFilter`) have the same text as in main.
- Affected methods: all JDBC methods (indirectly).

## V1-1 Record-key index changes

### V1-1a Drop `(record_key, created_at)`
- DDL files: h2, postgres (`DROP` of `idx_outbox_record_record_key_created` = line removed from CREATE), mysql/maria (`idx_outbox_record_record_key_created`), mssql (same name), oracle (`idx_outbox_rec_key_cr`). JPA: `@Index idx_outbox_record_record_key_created` removed from `OutboxRecordEntity`.
- Before: `(record_key, created_at)`. After: removed. Existing databases: file only shows creation DDL, no DROP statement.
- Affected methods: `findIncompleteRecordsByRecordKey` (`WHERE record_key = ? AND status = ? ORDER BY created_at, id`), predecessor subqueries.

### V1-1b `(record_key, completed_at, created_at)` -> `(record_key, completed_at, created_at, id)`
- Names: h2/pg/my/maria/mssql `idx_outbox_record_record_key_completed_created` -> `idx_outbox_record_record_key_completed_created_id`; oracle `idx_outbox_rec_k_c_c` -> `idx_outbox_rec_k_c_c_id`; JPA `idx_outbox_record_key_completed_created` -> `idx_outbox_record_record_key_completed_created_id` (columns `recordKey, completedAt, createdAt, id`).
- Affected: predecessor `NOT EXISTS` (V1-4/5/7), V1-8 bulk load.

## V1-2 New incomplete-record / queue index (per dialect)

- h2: `CREATE INDEX IF NOT EXISTS idx_outbox_record_completed_created_id ON outbox_record (completed_at, created_at, id, partition_no, status, next_retry_at, record_key)`
- mysql/mariadb: same index inline in CREATE TABLE.
- postgres: `CREATE INDEX IF NOT EXISTS idx_outbox_record_created_id_incomplete ON outbox_record (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL;`
- sqlserver: filtered `CREATE INDEX idx_outbox_record_created_id_incomplete ON outbox_record (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL;` (guarded by `IF NOT EXISTS` on sys.indexes; created separately after table, so also created for existing installs only if the script is re-run).
- oracle: `CREATE INDEX idx_outbox_rec_status_created ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key)` (leads with status, no completed_at).
- JPA entity: `idx_outbox_record_completed_created_id (completedAt, createdAt, id)` (narrower than the h2/mysql DDL).
- Affected: `findRecordKeysInPartitions`, `findOldestCreatedAt...`, `findOldestDueNextRetryAt` (outer scan); every insert/update that changes completed_at/status/next_retry_at/partition_no/record_key maintains it (PG/MSSQL partial/filtered: only rows with `completed_at IS NULL`).

## V1-3 PostgreSQL extended statistics
- `CREATE STATISTICS IF NOT EXISTS outbox_record_status_completed_stx (mcv) ON status, completed_at FROM outbox_record;` (postgres only). No query change.

## V1-4 `findRecordKeysInPartitions`, `ignoreRecordKeysWithPreviousFailure = true`
Before (all dialects, main):
```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key
        AND older.completed_at IS NULL
        AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))
GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC
```
(then `withMaxRows(batchSize)`; param order status/partitions/now).
After, base/H2/PG/MySQL/MariaDB/MSSQL/(portable):
```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key
        AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at
        AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id)))
ORDER BY o.created_at, o.id
<LIMIT :limit | OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY (mssql) | none (portable)>
```
Oracle: no `o.completed_at IS NULL`; `ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id FETCH FIRST :limit ROWS ONLY`.
Plus `.param("limit")` and `withMaxRows` (reordered after params). No GROUP BY / MIN. Result: one key per head record (no duplicates). Semantics difference: result row count limit now applies before grouping (no grouping).
JPA: JPQL equivalent (`and o.completedAt is null`, `earlier.createdAt <= o.createdAt`, `order by o.createdAt asc, o.id asc`, no `group by`; select only `o.recordKey`); `setMaxResults` in unchanged caller code.

## V1-5 `findRecordKeysInPartitions`, `ignoreRecordKeysWithPreviousFailure = false`
Before: `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC` (+withMaxRows).
After (base/H2/PG/MySQL/MariaDB):
```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key
     AND earlier.completed_at IS NULL AND earlier.status = :status AND earlier.next_retry_at <= :now
     AND earlier.created_at <= o.created_at
     AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id)))
ORDER BY o.created_at, o.id LIMIT :limit
```
SQL Server: the main query text kept, with `OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY` appended after `ORDER BY MIN(o.created_at) ASC` (`GROUP BY o.record_key`). Oracle: as above without `o.completed_at IS NULL`, `ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id FETCH FIRST :limit ROWS ONLY`.
JPA: same rewrite in JPQL (not exists with `earlier.status = :status`, `earlier.nextRetryAt <= :now`).

## V1-6 `findOldestCreatedAtByPartitionsStatus` (JDBC `oldestCreatedAtByPartitionsStatus`)
Before: `SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status`.
After: `SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at <LIMIT :limit | FETCH FIRST :limit ROWS ONLY | OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY | none>` with `limit=1`, `withMaxRows(1)`; no row -> null.
JPA: `select o.createdAt ... and o.completedAt is null order by o.createdAt`, `setMaxResults(1)`, `resultList.firstOrNull()` (before: `select min(o.createdAt)`, `singleResult`).

## V1-7 `findOldestDueNextRetryAt`, with previous-failure filter
Before: `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))`.
After (base/H2/PG/MySQL/MariaDB/MSSQL): `SELECT o.next_retry_at ... same WHERE, predecessor with added earlier.created_at <= o.created_at ... ORDER BY o.next_retry_at <limit clause>`, `limit=1`, `withMaxRows(1)`.
Oracle: `SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at ... ORDER BY SYS_EXTRACT_UTC(o.next_retry_at) FETCH FIRST :limit ROWS ONLY`.
JPA: `select o.nextRetryAt ... order by o.nextRetryAt asc`, `setMaxResults(1)`. Without-filter variant (`MIN(o.next_retry_at)`) unchanged in SQL (JPA code restructured into explicit branch, same query), but JDBC without-filter path now also binds `limit` and `withMaxRows(1)` (the statement is `SELECT MIN(...)`, unchanged).

## V1-8 `findIncompleteRecordsByRecordKeys` bulk load (JDBC `findByKeysAndStatus`)
Before: `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id`.
After (base/H2/MySQL/MariaDB/MSSQL/Oracle): `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id`.
PostgreSQL only:
```sql
SELECT o.* FROM (SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k) k
CROSS JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status
   AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id) o
```
(no overall ORDER BY; one index probe per key). JPA bulk-load query unchanged. Size: up to `MAX_RECORD_KEYS_PER_FETCH` keys.
Per-key `findIncompleteRecordsByRecordKey`: SQL unchanged (KDoc now says NEW only).

## V1-9 Row limit in SQL for retention/prune queries (JDBC)
Statements: `findByStatusAndCompletedBefore`, `findByStatusAndNextRetryBefore`, `findIdsByStatusAndCompletedBefore`, `findIdsByStatusAndNextRetryBefore` (methods `findCompletedRecordsOlderThan`-style finders and `deleteCompleted/FailedRecordsOlderThan(limit)`).
Before: `SELECT [*|id] FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at` (and `next_retry_at` analogs), limited only by `withMaxRows(limit)`.
After: same text plus `LIMIT :limit` (H2/PG/MySQL/MariaDB), `OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY` (mssql), `FETCH FIRST :limit ROWS ONLY` (ora), nothing for portable; `limit` param bound, `withMaxRows(limit)` kept. JPA unchanged.

## V1-10 New method `countByStatuses(Set<OutboxRecordStatus>)`
- Interface default (loops `countByStatus`), `@since 1.12.0`; JDBC and JPA override.
- JDBC: `SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status`; empty set -> no query; missing statuses -> 0.
- JPA: `select o.status, count(o) from OutboxRecordEntity o where o.status in :statuses group by o.status`.
- `countByStatus` SQL unchanged.

## V1-11 Non-SQL: metrics collector (observability module)
- Files: `OutboxMetricsCollector.kt`, `OutboxMetricsProperties.kt`, spring-configuration-metadata.json.
- New property `namastack.outbox.observability.metrics.record-statuses`, default `{NEW, FAILED}`. Before: one `countByStatus` per every status in `OutboxRecordStatus.entries` per collection (default refresh 15s), so 3 queries including COMPLETED. After: one `countByStatuses(recordStatuses)` per collection (1 query, no COMPLETED gauge by default); empty set -> no record-count query. Other per-collection queries (partition counts, oldest age, due retry) unchanged except via V1-6/V1-7.
- Depends on V1-10.

## Other
- Tests changed/added (JdbcOutboxRecordQueriesTest, bulk-loading and metrics tests) and reference docs: not part of the revert items.
