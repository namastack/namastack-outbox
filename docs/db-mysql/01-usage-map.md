# `outbox_record` usage map for MySQL

Variants: `my-main` = `/home/zamo/git/outbox-wt-my-main` (f4446c8c), `my-opt` = `/home/zamo/git/outbox-wt-my-opt` (270c42cf, branch
`feature/db-jdbc-optimization`). Sources: Kotlin code, DDL, tests, ADR-0005, the extracted SQL catalogs
(`harness/catalog/{my-main,my-opt}/mysql/`) and the Connector/J 9.7.0 and MariaDB Connector/J 3.5.10 source jars from the Gradle cache.
Query ids are the ids of `02-scenarios.md`. Statements below are the exact text of the catalogs, with `:name` placeholders as sent by
Spring `JdbcClient` (which expands them to `?` and `IN (?,?,...)` lists).

## 1. Table on MySQL

DDL file: `namastack-outbox-jdbc/src/main/resources/schema/mysql/outbox-tables.sql`. Only the index list differs between the variants;
the columns are identical.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `VARCHAR(255)` | NOT NULL | primary key (clustered). UUID string in practice. |
| `status` | `VARCHAR(20)` | NOT NULL | `NEW`, `COMPLETED`, `FAILED` |
| `record_key` | `VARCHAR(255)` | NOT NULL | ordering key; hashed to `partition_no` |
| `record_type` | `VARCHAR(255)` | NOT NULL | |
| `payload` | `LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` | NOT NULL | explicit column charset/collation |
| `context` | `LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` | NULL | explicit column charset/collation |
| `created_at` | `TIMESTAMP` | NOT NULL | second precision (no fractional digits) |
| `completed_at` | `TIMESTAMP` | NULL | |
| `failure_count` | `INT` | NOT NULL | |
| `failure_reason` | `VARCHAR(1000)` | NULL | |
| `next_retry_at` | `TIMESTAMP` | NOT NULL | |
| `partition_no` | `INT` | NOT NULL | |
| `handler_id` | `VARCHAR(1000)` | NOT NULL | |

Engine `InnoDB`; no `CHARSET`/`COLLATE` on the table. All `VARCHAR` columns (`id`, `status`, `record_key`, `record_type`,
`failure_reason`, `handler_id`) therefore take the schema/server default. On MySQL 8.4 with default settings that is `utf8mb4` with
`utf8mb4_0900_ai_ci` (the instance/database default, depends on the deployment; the harness image default applies). Only `payload` and
`context` carry the explicit `utf8mb4_unicode_ci`, and neither is indexed. Consequence: comparisons on `record_key`/`id`/`status` use the
accent-insensitive, case-insensitive, PAD-less `0900_ai_ci` collation (so `record_key` values differing only by case/accents compare equal
in `=`, `GROUP BY`, `IN`, and unique-ness of the `id` primary key); this is a property of both variants. Ordering by `id` as tie-break uses
the same collation, and the application's tie-break (Kotlin string compare is not involved; only SQL compares) is whatever the collation
gives.

### Indexes

`my-main` (5 secondary indexes):

| Index | Columns |
|---|---|
| `idx_outbox_record_record_key_created` | `(record_key, created_at)` |
| `idx_outbox_record_partition_status_retry` | `(partition_no, status, next_retry_at)` |
| `idx_outbox_record_status_retry` | `(status, next_retry_at)` |
| `idx_outbox_record_status_created` | `(status, created_at)` |
| `idx_outbox_record_record_key_completed_created` | `(record_key, completed_at, created_at)` |

> **CORRECTION 2026-11-06 (re-pin to 270c42cf):** the `my-opt` index list below is the 2a2663de DDL. At 270c42cf it is: `idx_outbox_record_status_created_id (status, created_at, id, partition_no, next_retry_at, record_key)`, `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`, `idx_outbox_record_status_retry_partition (status, next_retry_at, partition_no)`, `idx_outbox_record_status_partition (status, partition_no)`. Still 4 indexes; `status_completed_*` names no longer exist.

`my-opt` (4 secondary indexes):

| Index | Columns |
|---|---|
| `idx_outbox_record_status_completed_created_id` | `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` |
| `idx_outbox_record_key_completed_created_id` | `(record_key, completed_at, created_at, id)` |
| `idx_outbox_record_status_completed_retry` | `(status, completed_at, next_retry_at, partition_no)` |
| `idx_outbox_record_status_completed_partition` | `(status, completed_at, partition_no)` |

No unique, foreign-key or check constraint other than the primary key. The tables `outbox_instance` and `outbox_partition` are not part
of this study.

### InnoDB consequences (general knowledge)

- The primary key is the clustered index: rows are stored in `id` order. UUIDv4 ids give random insertion points (page splits, a
  buffer-pool working set that spans the whole table) for inserts; reads by `id` (updates, deletes) are one B-tree descent.
- Every secondary index entry stores its key columns plus the primary key `id` (omitted when `id` is already an index column, as in
  `idx_..._created_id`). `id VARCHAR(255)` utf8mb4 is up to 1020 bytes per entry in every secondary index. A secondary read that needs a
  column outside the index does a clustered lookup per row (random I/O) unless the index is covering.
- `TIMESTAMP` has second precision: many records of one key can share `created_at`; the `id` tie-break matters (no sub-second order). Range
  `2038-01-19` limit and session-time-zone conversion apply (general knowledge).
- Index key limit: 3072 bytes per index for DYNAMIC/COMPRESSED rows (the default row format). utf8mb4 uses up to 4 bytes per character, so
  `record_key` is up to 1020 bytes and `id` 1020 bytes, `status` 80 bytes. `idx_..._status_completed_created_id` in `my-opt` is at most
  80 + 5 + 5 + 1020 + 4 + 4 + 1020 plus null/length bytes = about 2.2 KB, under the limit. (`handler_id VARCHAR(1000)` is up to 4000 bytes and
  is not indexed anywhere.) Entry width is the declared maximum only for sorting/memory; stored entries use actual length.
- Updates of a non-indexed column are in place on the clustered record. Updating an indexed column delete-marks the old secondary entry and
  inserts a new one (purge removes the delete-marked entry later); the clustered record also keeps an undo record. See section 6.
- `COUNT(*)` and `MIN()` need an index scan (no stored row count); a secondary index scan reads that index only.

## 2. Field semantics and invariants

Source: `namastack-outbox-core` (`OutboxRecord`, `OutboxService`, processor chain).

- **Create** (`OutboxService.schedule` -> `create`, inside the caller's transaction): `status = NEW`, `completed_at = NULL`,
  `failure_count = 0`, `failure_reason = NULL`, `next_retry_at = created_at = now` (the scheduling clock, one value per fan-out), one
  record per matching handler (`handler_id`), `record_key` = business key (or encoded storage key when processing groups are on),
  `partition_no = PartitionHasher.getPartitionForRecordKey(record_key)` = `abs(MurmurHash3 x86-32(record_key)) % 256`
  (`PartitionHasher.TOTAL_PARTITIONS = 256`).
- **NEW -> COMPLETED**: `OutboxRecordProcessor.completeRecord` after the handler succeeds: `markCompleted` sets `status = COMPLETED`,
  `completed_at = now`, then `updateProcessingState`. With `processing.delete-completed-records=true` the record is deleted by
  `deleteById` instead (no COMPLETED rows exist).
- **NEW -> NEW (retry)**: `PrimaryOutboxRecordProcessor` increments `failure_count` and records `failure_reason` on a handler failure;
  `RetryOutboxRecordProcessor` then sets `next_retry_at = now + delay` and calls `updateProcessingState`. `status` stays `NEW`,
  `completed_at` stays `NULL`.
- **NEW -> FAILED**: after retries are exhausted or the failure is not retryable and no fallback completes the record,
  `PermanentFailureOutboxRecordProcessor`: `markFailed` (`status = FAILED`), `updateProcessingState`. `completed_at` stays `NULL`,
  `failure_count`/`failure_reason` hold the last failure, `next_retry_at` keeps the last scheduled attempt. A successful fallback completes
  the record via `completeRecord` (NEW -> COMPLETED).
- `COMPLETED` and `FAILED` are terminal; there is no automatic transition out of FAILED. Retention deletes terminal rows (P3).
- **Invariant `completed_at IS NULL` <=> status in (NEW, FAILED)** (ADR-0005 relies on it; `COMPLETED` rows always have `completed_at`
  set). The invariant is maintained only by `markCompleted`, never enforced by the database.
- **`next_retry_at`** is NOT NULL for all statuses; it is "due" when `next_retry_at <= now`. Only `NEW` rows with `next_retry_at <= now` are
  picked by the key query. FAILED rows keep the last value (retention for FAILED is driven by it).
- **Ordering per `record_key`**: records of one key are processed in `(created_at, id)` order. A key is eligible for processing (strict
  mode, `stopOnFirstFailure=true`, default) only if its first incomplete record (any status NEW or FAILED, due or not) is itself a due NEW
  record; an earlier FAILED or not-yet-due NEW blocks the key. In non-strict mode the earliest due NEW record of the key counts
  (my-opt) / every key with a due NEW record counts (my-main, `GROUP BY`).
- **`handler_id`**: stable handler identity persisted with the record, so routing survives restarts. Not queried by any repository SQL.

```
            create (NEW, completed_at NULL, failure_count 0, next_retry_at = created_at)
                          |
                          v
                 +--------------------+  handler error, retries left
                 |        NEW         |-----------------------------+
                 | completed_at NULL  |  failure_count++, next_retry_at = now+delay
                 +--------------------+<----------------------------+
                  |                 |
        handler OK / fallback OK    retries exhausted / not retryable, no fallback
                  |                 |
                  v                 v
          +-------------+     +-------------+
          |  COMPLETED  |     |   FAILED    |    (completed_at NULL)
          | completed_at|     +-------------+
          |   = now     |
          +-------------+  (or deleteById when delete-completed-records=true)
```

## 3. Dialect detection

### `my-opt`

- `JdbcDatabaseType.detect(dataSource)` (`config/JdbcDatabaseType.kt`) opens a connection (or reuses the transaction-bound one), reads
  `DatabaseMetaData.getDatabaseProductName()`, passes it through Spring `JdbcUtils.commonDatabaseName(...)` and maps (case-insensitive)
  via `from(String)`: `postgresql` -> PostgreSQL, `mysql` -> MySQL, `h2` -> H2, `mariadb` -> MariaDB, `microsoft sql server` -> SQLServer,
  `oracle` -> Oracle. Unknown products return `null` (no exception), which `JdbcOutboxRecordQueriesFactory.create(null, ...)` maps to
  `DefaultJdbcOutboxRecordQueries(tableName)` with an empty row-limit clause (portable SQL, the driver limit via `withMaxRows`).
- `JdbcOutboxRecordQueriesFactory.create` selects `MySqlJdbcOutboxRecordQueries` for `JdbcDatabaseType.MySQL` and
  `MariaDbJdbcOutboxRecordQueries` for `MariaDB`. Both are `DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit")` with no override
  (MySQL and MariaDB SQL are textually identical; the classes are separate so they can diverge). The factory is resolved lazily on first
  repository use (`lazy(queries)`), so construction opens no connection.
- Schema initialization (`JdbcOutboxSchemaAutoConfiguration`) uses the strict `JdbcDatabaseType.from(dataSource)` (throws on unsupported
  products) and runs `schemaLocation` of the type: `schema/mysql/outbox-tables.sql` for MySQL, `schema/mariadb/outbox-tables.sql` for
  MariaDB. The DDL of the two files differs only by `DEFAULT NULL` on three nullable columns and trailing blank lines; the index lists are
  identical.

### `my-main`

`JdbcDatabaseType` carries `rowLimitClause` as a constructor property (`MySQL` and `MariaDB`: `LIMIT :limit`; others
`FETCH FIRST :limit ROWS ONLY` etc.). `JdbcOutboxRecordRepository` takes `databaseType: () -> JdbcDatabaseType` (lazy) and reads
`databaseType().rowLimitClause` into the two key-selection templates; `JdbcOutboxAutoConfiguration` and `JdbcOutboxPersistenceFactory`
pass `JdbcDatabaseType.from(dataSource)` (strict, throws on unsupported products). The mapping product name -> type is the same as in
`my-opt`. For MySQL/MariaDB `main` therefore also sends `LIMIT :limit` in the two key queries. All other statements are fixed in the
repository.

### MariaDB server reached through MySQL Connector/J (or `useMysqlMetadata=true`)

- **MySQL Connector/J** (9.7.0 source, `com/mysql/cj/jdbc/DatabaseMetaData.getDatabaseProductName()`) returns the constant `"MySQL"`
  regardless of the server. A MariaDB server connected with `jdbc:mysql://...` is therefore detected as `JdbcDatabaseType.MySQL`: it gets
  `MySqlJdbcOutboxRecordQueries` and the `schema/mysql` DDL.
- **MariaDB Connector/J** (3.5.10 source, `DatabaseMetaData.getDatabaseProductName()`) returns `"MariaDB"` for a MariaDB server unless the
  URL has `useMysqlMetadata=true`, in which case it returns `"MySQL"`. With `useMysqlMetadata=true` MariaDB again gets the MySQL class and
  DDL. (Also: a MySQL server through MariaDB Connector/J returns `"MySQL"`, the same result as with Connector/J.)
- `commonDatabaseName` does not alter `"MySQL"`/`"MariaDB"`.
- **Implications.** Every MySQL statement and index set this study changes also reaches those MariaDB deployments:
  1. The MySQL `outbox_record` indexes are what the MariaDB server executes; the MySQL SQL text runs on the MariaDB optimizer (different
     planner: e.g. no hash join, different semi-join/anti-join (`NOT EXISTS`) strategies, different handling of index condition pushdown,
     range-to-ref and `ORDER BY ... LIMIT` index choice; MariaDB's descending-index and `IN`-list range optimization differ). The MySQL
     tuning must be checked on MariaDB (the separate MariaDB study) before it is declared safe there.
  2. The MariaDB-specific class `MariaDbJdbcOutboxRecordQueries` and `schema/mariadb` (the `DEFAULT NULL` on `completed_at` etc.) are
     not used; a MariaDB server with MySQL-flavoured DDL works (the difference is cosmetic). The `TIMESTAMP` semantics on MariaDB differ
     (`explicit_defaults_for_timestamp` defaults to OFF before MariaDB 10.10: `TIMESTAMP NOT NULL` columns without a default get
     `DEFAULT current_timestamp ON UPDATE current_timestamp` for the first column: `created_at` is the first TIMESTAMP column and so may be
     silently rewritten on every update that does not set it; `updateProcessingState` does not touch `created_at`, so the auto-update would
     change `created_at`!). This is a risk that exists in the shipped `schema/mysql` DDL on MariaDB regardless of this study; flag only.
  3. Any new index or statement added for MySQL changes MariaDB behaviour at the same time, with no dialect switch to separate them.
  4. Changes only to `MariaDbJdbcOutboxRecordQueries` or `schema/mariadb` do not reach Connector/J-MySQL deployments.
- Detection by server version string (`getDatabaseProductVersion` containing "MariaDB") is not used.

## 4. Method catalog (`OutboxRecordRepository`, JDBC implementation)

Interface: `namastack-outbox-core/.../OutboxRecordRepository.kt`. JDBC: `namastack-outbox-jdbc/.../JdbcOutboxRecordRepository.kt`
(`my-main` statements are `private val ...Query` fields; `my-opt` statements come from `query/DefaultJdbcOutboxRecordQueries.kt` through
`MySqlJdbcOutboxRecordQueries`). Line numbers refer to the `my-opt` tree unless prefixed. Frequencies use defaults: fixed polling every
2 s (adaptive 1..8 s), `batchSize = 10`, `MAX_RECORD_KEYS_PER_FETCH = 1000`, metrics `refresh-interval = 15 s`.

### P1: scheduling and execution

**`findRecordKeysInPartitions`** (id `keys_strict` / `keys_nonstrict`; HOT)
- Purpose: up to `batchSize` record keys with a due NEW record in the owned partitions. Caller:
  `OutboxProcessingScheduler.loadRecordKeys` (`namastack-outbox-core/.../OutboxProcessingScheduler.kt:163`), inside
  `partitionCoordinator.withStableAssignments`. Trigger: once per poll per instance (every 2 s with fixed polling, also when idle), always
  enabled. Strict variant when `processing.stop-on-first-failure=true` (default).
- Parameters: `:partitions` (list of owned partition numbers, up to 256, expanded to `IN (?,...)`), `:status` = `NEW`, `:now`, `:limit` =
  `batchSize`. Result: up to `batchSize` keys (10 by default). Plain `SELECT` without `FOR UPDATE`, no explicit transaction (autocommit,
  REPEATABLE READ snapshot per statement, no locks).
- SQL, `my-main` strict:
  `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND ( older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id) ) ) ORDER BY o.created_at ASC, o.id ASC LIMIT :limit`
- SQL, `my-opt` strict:
> **CORRECTION 2026-11-06 (re-pin to 270c42cf):** the `my-opt` SQL quoted in this method (and the index discussion below) is 2a2663de; at 270c42cf the outer `o.completed_at IS NULL` is absent in `keys_strict`, `keys_nonstrict`; see `harness/catalog/my-opt/mysql/` for the exact text.

  `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.created_at, o.id LIMIT :limit`
  and additionally `withMaxRows(batchSize)` (line 226). `main` has no `setMaxRows` on this call.
- SQL, `my-main` non-strict: `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC LIMIT :limit`
- SQL, `my-opt` non-strict: `... AND o.completed_at IS NULL AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.status = :status AND earlier.next_retry_at <= :now AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.created_at, o.id LIMIT :limit` (+ `withMaxRows(batchSize)`).
- Index that can serve it (hypotheses for the plan phase): `main`: outer `(partition_no, status, next_retry_at)` range per partition in
  the `IN` list (up to 256 ranges), then a filesort over all matching due rows (the `ORDER BY created_at, id` is not index-ordered; MySQL
  cannot stop early) and for each candidate a probe of the subquery via `(record_key, created_at)` or `(record_key, completed_at,
  created_at)` (clustered lookups for `id` only when `created_at` ties; `id` is in every secondary entry). The anti-join may be turned into
  a hash anti-join (MySQL 8.0.18+). `opt`: outer scan of `(status, completed_at, created_at, id, ...)` with `status = 'NEW' AND
  completed_at IS NULL` as the prefix, rows in `(created_at, id)` order, so the `LIMIT` can stop the scan early; `partition_no`,
  `next_retry_at`, `record_key` are in the index (covering for the outer block); the subquery probes `(record_key, completed_at,
  created_at, id)` (`completed_at IS NULL` + `created_at` range, covering). Early termination depends on the optimizer choosing this
  index for the outer query instead of a partition range plan.
- Non-strict `main`: aggregation over all due rows of the owned partitions, temp table/filesort.

**`findIncompleteRecordPages`** (id `pages`; HOT)
- Purpose: bulk load of the NEW records of the selected keys. Caller: `OutboxProcessingScheduler.processBatch` (`.../OutboxProcessingScheduler.kt:174`),
  chunked by `MAX_RECORD_KEYS_PER_FETCH` (1000). Trigger: once per poll that returned keys (so up to once per 2 s); returns no rows and
  no query when the key list is empty.
- Parameters: `:recordKeys` (up to `batchSize` keys, `IN (?,...)`), `:status` = `NEW`. Result: all NEW records of those keys (all columns
  incl. `payload`/`context`, sorted by `record_key, created_at, id`); size = keys x NEW records per key (hot keys: thousands, S5).
- `main`: `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id`
- `my-opt`: same plus `AND completed_at IS NULL` (the FAILED rows are also incomplete but excluded by `status = 'NEW'`).
- Index: `main` `(record_key, created_at)` or `(record_key, completed_at, created_at)`: per key range, then clustered lookup for every row (to
  check `status` and to read the columns), `ORDER BY` served per key range (merge of ranges, or filesort). `opt`
  `(record_key, completed_at, created_at, id)` with `completed_at IS NULL` equality: ranges per key in `(created_at, id)` order, `status`
  still checked on the clustered row; rows of FAILED keys are read and filtered.

**`updateProcessingState`** (ids `upd_complete`, `upd_retry`, `upd_fail`; WRITE)
- Purpose: persist status, completion, failure count/reason, next retry after processing. Callers: `OutboxRecordProcessor.completeRecord`
  (`.../processor/OutboxRecordProcessor.kt:73`), `RetryOutboxRecordProcessor` (`:43`), `PermanentFailureOutboxRecordProcessor` (`:31`).
  Trigger: once per processed record (complete) plus once per retry and once per permanent failure. Within the repository's own
  `TransactionTemplate` transaction (single statement), row lock on the clustered record.
- SQL (identical in both variants, whitespace aside):
  `UPDATE outbox_record SET status = :status, completed_at = :completedAt, failure_count = :failureCount, failure_reason = :failureReason, next_retry_at = :nextRetryAt WHERE id = :id`
- Parameters: 5 values + `id`. Result: 1 row updated. Access: primary key lookup; secondary-index maintenance per section 6. A no-op
  assignment (same value) of an indexed column does not modify the index entry (InnoDB compares old/new values).

**`create`** (id `insert`; WRITE)
- Purpose: insert a new NEW record. Caller: `OutboxService.schedule` (`namastack-outbox-core/.../OutboxService.kt:500`) in the caller's
  transaction (joins the application's business transaction). Trigger: once per record per handler.
- SQL: `INSERT INTO outbox_record (id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id) VALUES (...)`; identical in both (my-opt formatting differs by whitespace only).
- Cost: one clustered insert (random position for UUIDv4 `id`) plus one insert per secondary index (5 in `main`, 4 in `opt`).

**`deleteById`** (id `delete_by_id`; WRITE, only with `delete-completed-records=true`)
- Caller: `OutboxRecordProcessor.completeRecord` (`:66`). Once per completed record. `DELETE FROM outbox_record WHERE id = :id` (identical).
  Delete-marks the clustered record and every secondary entry; purge removes them later.

**`save` / `update`** (full-row `UPDATE ... WHERE id` then `INSERT` fallback): used by the API/tests; the processing chain uses
`updateProcessingState`. Not part of the studied query ids.

### P2: metrics collector (`namastack-outbox-observability`, `OutboxMetricsCollector.refresh`, every 15 s per instance after the previous run finishes)

| Method | `main` call | `my-opt` call |
|---|---|---|
| status counts | `countByStatus(status)` for **all** statuses (NEW, COMPLETED, FAILED) = 3 queries (collector line 148) | `countByStatuses(recordStatuses)` = **1** grouped query; `record-statuses` default `{NEW, FAILED}` (collector line 152; `OutboxMetricsProperties.recordStatuses`) |
| pending | `countRecordsByPartitions(partitions, NEW)` | same |
| oldest created | `findOldestCreatedAtByPartitions(partitions, NEW)` | `findOldestCreatedAtByPartitions(partitions)` (status fixed to NEW) |
| overdue age | `findOldestDueNextRetryAtByPartitions(partitions, NEW, now, strict)` | same without the `status` argument, with `withMaxRows(1)` |

All run with the instance's assigned partitions (`IN (...)`, empty set short-circuits). `main`: 3 + 3 = 6 queries per refresh;
`my-opt`: 1 + 3 = 4 (scenario doc "6 queries per 15 s" is the `main` count; with default `record-statuses` the branch drops the
`COMPLETED` count, which scales with history). Frequency 4 queries/15 s/instance on the branch. Config: `namastack.outbox.observability.metrics.enabled`
(default true), `refresh-interval`, `max-age`, `record-statuses` (since 1.12.0).

- `countByStatuses` (ids `count_statuses` NEW+FAILED, `count_statuses_all` all three): `SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status`.
  Not present on `main` (n/a). Index (opt): `(status, completed_at, partition_no)` or `(status, completed_at, ...)` range scans, index-only
  (`status IN ('NEW','FAILED')`), loose scan not usable for COUNT; cost grows with the number of NEW + FAILED rows (with `COMPLETED` it grows
  with all history). `main` equivalent: three `countByStatus` (ids `count_new`, `count_failed`, `count_completed`):
  `SELECT COUNT(*) FROM outbox_record WHERE status = :status`; index `(status, retry/created)`, index-only scan of the whole status range.
- `countRecordsByPartitions` (id `count_partitions`): `SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status`
  (identical in both). `main`: `(partition_no, status, next_retry_at)` ranges (index-only, one range per partition). `opt`: no index
  starts with `partition_no`; `(status, completed_at, partition_no)` scans the `NEW` range (all NEW rows, not only owned partitions) with
  `partition_no` checked as an index condition (the `status = 'NEW'` prefix, `completed_at` not constrained here), index-only.
> **CORRECTION 2026-11-06 (re-pin to 270c42cf):** the `opt` forms of `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict` and their index text are 2a2663de. At 270c42cf: `oldest_created` = `SELECT created_at ... WHERE partition_no IN AND status ORDER BY created_at LIMIT :limit` (no `completed_at`, limit 1 + `withMaxRows(1)`); `oldest_due_nonstrict` = `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT :limit` (no `completed_at`); `oldest_due_strict` has no outer `completed_at IS NULL`.

- `findOldestCreatedAtByPartitions` (id `oldest_created`): `main`: `SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status`; `opt`: adds `AND completed_at IS NULL`. `main`
  index `(partition_no, status, next_retry_at)` does not hold `created_at` (row lookups) or `(status, created_at)` with a partition filter
  on the clustered row; `opt` `(status, completed_at, created_at, id, partition_no, ...)` with `status='NEW' AND completed_at IS NULL` prefix
  yields `created_at` in order and `partition_no` in-index, so the first row of an owned partition ends the scan if the optimizer uses it as a
  min-scan (for `IN` over a non-prefix column MySQL evaluates this as a range/filter scan, not a loose scan; hypothesis).
- `findOldestDueNextRetryAtByPartitions` (ids `oldest_due_strict`, `oldest_due_nonstrict`): `main` strict: `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE ... AND o.next_retry_at <= :now AND NOT EXISTS (<older subquery as in keys_strict>)`;
  `main` non-strict: `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now`.
  `opt` strict: `SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS ( ... same subquery as keys_strict ... ) ORDER BY o.next_retry_at LIMIT :limit` (limit 1 + `withMaxRows(1)`);
  `opt` non-strict: `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL` (no LIMIT in the SQL, `withMaxRows(1)` and a surplus bound `limit` param). Index: `main` `(partition_no, status, next_retry_at)`; `opt`
  `(status, completed_at, next_retry_at, partition_no)` (`status`/`completed_at` equality prefix, `next_retry_at` order, `partition_no` in-index).
  The strict form evaluates the subquery per candidate in `next_retry_at` order until the first row without predecessor.

**Deprecated metrics module** (not a target, measured with MON): `OutboxRecordMetricsMeterBinder` gauge `outbox.records.count` ->
`countByStatus` (`namastack-outbox-metrics/.../OutboxRecordMetricsMeterBinder.kt:36`, ids `count_new/count_failed/count_completed`) on
every scrape; `OutboxPartitionMetricsProvider.getProcessingStats` -> `countRecordsByPartition(partition, NEW)` once per assigned partition
(id `count_partition_single`: `SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status`, identical).

### P3: retention and administration (not measured in this study)

SQL unchanged for the statements marked "same". Briefly:

- `findCompletedRecords(before, limit)` / `findFailedRecords(lastRetryBefore, limit)` / `deleteCompletedRecords` / `deleteFailedRecords`
  (ids `find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before`, then `delete_by_ids`): select
  `... WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at` (completed) or `next_retry_at < :cutoff ORDER BY next_retry_at`
  (failed) with `withMaxRows(limit)` on `main`; `opt` appends `LIMIT :limit` and still calls `withMaxRows(limit)`. The delete variants read ids
  in a transaction, then `DELETE FROM outbox_record WHERE id IN (:ids)` (id `delete_by_ids`). No caller in the main sources of this
  repository (the interface methods are called by the retention scheduler outside the files listed here / by users); config:
  `retention` properties in `OutboxProperties`.
- `findPendingRecords`, `findCompletedRecords()`, `findFailedRecords()` (ids `find_pending_all`, `find_failed_all`): full status scan
  `SELECT * ... WHERE status = :status ORDER BY created_at, id` (same in both).
- `findIncompleteRecordsByRecordKey` (id `find_incomplete_by_key`): `... WHERE record_key = :recordKey AND status = :status [AND completed_at IS NULL] ORDER BY created_at, id` (`opt` adds `completed_at IS NULL`).
- `deleteByStatus` (id `delete_by_status_failed`), `deleteByRecordKeyAndStatus` (id `delete_by_key_status`): actuator endpoint
  `OutboxActuatorEndpoint` (`namastack-outbox-actuator`), admin only; same SQL.

### `setMaxRows` on MySQL Connector/J

> **Correction (orchestrator, after phase 2):** the server's `general_log` shows that Connector/J 9.7.0 **does** send
> `SET sql_select_limit=n` before and `SET sql_select_limit=DEFAULT` after every execution of a statement with `setMaxRows(n)`, as two
> separate `Query` commands, also when the SQL already contains `LIMIT` (`results/validation/VALIDATION.md`, item 4;
> `results/validation/general_log.txt`). The source reading below is wrong on this point (the statement is built in the session layer
> with a lower-case variable name). `main` sends no `setMaxRows` for its P1/P2 queries.

Source read: mysql-connector-j 9.7.0 sources jar (`~/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j/9.7.0/.../*-sources.jar`),
the version in the Gradle cache. Findings (verified in source for 9.7.0; not checked for other versions):

- A grep for `SQL_SELECT_LIMIT` in the whole source tree finds **no occurrence**: Connector/J 9.7.0 sends **no**
  `SET SQL_SELECT_LIMIT=n` / `SET SQL_SELECT_LIMIT=DEFAULT` statements and no extra round trips for `setMaxRows`. (Older driver versions
  did; the harness general-log check in phase 2 should confirm for the version it uses.)
- `StatementImpl.setMaxRows(int)` -> `setLargeMaxRows` stores `maxRows` in the statement. `ClientPreparedStatement.execute`/`executeQuery`
  call `locallyScopedConn.setSessionMaxRows(first char of statement == 'S' ? maxRows : -1)` (only a client field in
  `NativeSession`/`CoreSession`, `sessionMaxRows`) and pass `maxRows` to `executeInternal` -> `NativeSession.execSQL` -> `NativeProtocol.sendQueryPacket`
  -> `readAllResults` -> `TextResultsetReader.read(maxRows, ...)`. In the non-streaming path the reader still reads **every row packet**
  from the socket and only adds the first `maxRows` rows to the in-memory list (`if (maxRows == -1 || rowList.size() < maxRows)`); the rest are
  read and discarded. `BinaryResultsetReader` (server-side prepared statements, `useServerPrepStmts=true`) does the same. With a streaming
  result (`fetchSize = Integer.MIN_VALUE`) and cursor-based fetch (`useCursorFetch`) the owner limit is checked in `ResultsetRowsStreaming` /
  `ResultsetRowsCursor`; Spring's `JdbcClient` does not enable these.
- Consequence: `setMaxRows` on Connector/J is **purely client-side truncation**. The server still executes the whole statement and sends all
  rows; the only saving is client memory. It does not add `LIMIT`, so `ORDER BY ... LIMIT` early termination in the server's optimizer
  (filesort with a limit, stop at N rows in an index scan) **does not happen** without `LIMIT` in the SQL. On `main` the retention queries (P3)
  have no `LIMIT`; the key query has `LIMIT :limit` already, so `main` and `opt` both get server-side limiting there. When the SQL already
  contains `LIMIT` (`opt`), `setMaxRows(n)` with the same `n` is a no-op on the wire (the server sends at most `n` rows). Applies to
  client-side prepared statements (default `useServerPrepStmts=false`, which is what `JdbcClient` uses) and equally to server-side ones.
- The statement-type guard `getFirstStmtChar() == 'S'` only matters for the session field; no statement is sent either way.
- Spring `JdbcClient.withMaxRows(n)` -> `JdbcTemplate.setMaxRows(n)` -> `Statement.setMaxRows`, applied via `applyStatementSettings`.

Status: verified from source for 9.7.0; wire behaviour (no extra statements) is to be confirmed with the server `general_log` in phase 2.

## 5. Priority grouping (ADR-0005)

| Group | Methods | Frequency | Why it matters |
|---|---|---|---|
| P1 scheduling/execution | `findRecordKeysInPartitions` (strict, non-strict), `findIncompleteRecordPages`, `create`, `updateProcessingState` (complete, retry, fail), `deleteById`, concurrent processing | key query 1 per poll per instance (0.5/s, even idle); pages once per non-empty poll; updates once per processed record; inserts once per scheduled record | Delivery latency and throughput; every index costs each insert and state update; hot path, runs continuously. |
| P2 metrics collector | `countByStatuses` (NEW + FAILED), `countRecordsByPartitions`, `findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions` | 4 queries (opt) / 6 (main) every 15 s per instance | Operator visibility; must be cheap enough for continuous use and not scale with history. Metrics module (`countByStatus`, `countRecordsByPartition`) is not a target. |
| P3 retention/admin | `findCompletedRecords`, `findFailedRecords`, `findPendingRecords`, `deleteCompletedRecords`, `deleteFailedRecords`, `deleteByIds`, `deleteByStatus`, `deleteByRecordKeyAndStatus`, `findIncompleteRecordsByRecordKey` | rare, off-peak, admin | Correct SQL and a SQL row limit; may be slow. Not run in this study. |

## 6. Write amplification on InnoDB

Per statement, "maintained" = a secondary index entry is delete-marked and a new one inserted (key column value changes), "unchanged" =
the entry stays untouched. Non-indexed columns (`failure_count`, `failure_reason`, `payload`, `context`, `record_type`, `handler_id`) only
rewrite the clustered record. Every update writes undo for the clustered record; a changed secondary key also creates a delete-mark + new
entry (purge later removes the old entries; with deletes it also creates history-list work).

| Operation | Columns whose value changes | `main` secondary indexes maintained | `my-opt` secondary indexes maintained |
|---|---|---|---|
| `updateProcessingState` complete (NEW -> COMPLETED) | `status`, `completed_at` (`next_retry_at` rewritten with the same value; `failure_count` normally unchanged) | `partition_status_retry`, `status_retry`, `status_created` (status); `record_key_completed_created` (completed_at) = 4 of 5; `record_key_created` untouched | `status_completed_created_id`, `status_completed_retry`, `status_completed_partition` (status, completed_at); `key_completed_created_id` (completed_at) = all 4 |
| `updateProcessingState` retry (NEW -> NEW) | `failure_count`, `failure_reason`, `next_retry_at` | `partition_status_retry`, `status_retry` (next_retry_at) = 2 | `status_completed_created_id` (contains `next_retry_at`), `status_completed_retry` = 2 |
| `updateProcessingState` fail (NEW -> FAILED) | `status` (+ `failure_count`/`failure_reason`, `next_retry_at` possibly) | `partition_status_retry`, `status_retry`, `status_created` = 3 (+ retry indexes if `next_retry_at` changed; it is unchanged by the fail step) | `status_completed_created_id`, `status_completed_retry`, `status_completed_partition` = 3 (`key_completed_created_id` has no `status`) |
| `create` | new row | 5 secondary inserts | 4 secondary inserts (one of them wide: includes `record_key` and `id`) |
| `deleteById` | row deleted | clustered + 5 secondary delete-marks | clustered + 4 |

> **CORRECTION 2026-11-06 (re-pin to 270c42cf):** the `my-opt` column names index names of 2a2663de. At 270c42cf the indexes are `status_created_id`, `status_retry_partition`, `status_partition` (status-bearing) and `key_completed_created_id` (completed_at): complete touches `status_created_id`, `status_retry_partition`, `status_partition` (status) + `key_completed_created_id` (completed_at) = all 4; retry (next_retry_at) touches `status_created_id`, `status_retry_partition` = 2; fail (status) touches the three status-bearing = 3. `completed_at` is no longer in the status indexes.

Notes: `status_completed_created_id` in `my-opt` carries 7 columns (about 2 KB at maximum length per entry, much less in practice),
so every maintenance of it writes a larger entry than any `main` index; both retry and complete rewrite it. The `my-opt` covering index
means key selection avoids clustered lookups (read benefit) at that cost. Redo/undo volume, change-buffer use (secondary-index changes are
buffered when the page is not in the buffer pool), and purge lag (`trx_rseg_history_len`) are measured in `writecost` / S9.

## 7. Branch change list (`main` f4446c8c .. branch 270c42cf, `git diff ... -- . ':!docs'`)

> Re-based 2026-11-06 from 2a2663de to 270c42cf (user decision). Changed items: B-1, B-3, B-4 (index definitions without `completed_at`), B-10, B-11, B-14 (outer `completed_at IS NULL` removed), B-13, B-15 (MIN replaced by `ORDER BY ... LIMIT`, no `completed_at`). B-2, B-5..B-9, B-12, B-16..B-22 unchanged. No item removed, none added.

Items touching MySQL statements, DDL or call paths. Each is independently revertible in code (note the index/statement dependencies in
the last column). "Query ids": ids from `02-scenarios.md`.

| Item | Description (what the code does) | Files | Query ids | Priority | Dependency note |
|---|---|---|---|---|---|
| **B-1** | New index `idx_outbox_record_status_created_id (status, created_at, id, partition_no, next_retry_at, record_key)` (no `completed_at`; was `status_completed_created_id` at 2a2663de) | `schema/mysql/outbox-tables.sql` | `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`, `count_*` (shares status prefix); writes `insert`, `upd_*`, `delete_by_id` | P1, P2 | Covering index for key selection and `oldest_created` (`ORDER BY created_at`); statements B-10..B-15 no longer filter outer `completed_at IS NULL` |
| **B-2** | New index `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)` | same | `keys_strict` (subquery), `keys_nonstrict` (subquery), `pages`, `oldest_due_strict` (subquery); writes | P1, P2 | Replaces the prefix of the two removed key indexes B-8, B-9 |
| **B-3** | New index `idx_outbox_record_status_retry_partition (status, next_retry_at, partition_no)` (no `completed_at`; was `status_completed_retry (status, completed_at, next_retry_at, partition_no)`) | same | `oldest_due_strict`, `oldest_due_nonstrict`, `keys_*` (alt.); writes `upd_retry`, `upd_complete`, `upd_fail` | P1, P2 | Serves `ORDER BY next_retry_at LIMIT` (B-14, B-15) |
| **B-4** | New index `idx_outbox_record_status_partition (status, partition_no)` (no `completed_at`; was `status_completed_partition (status, completed_at, partition_no)`) | same | `count_partitions`, `count_partition_single`, `count_statuses`; writes `upd_complete`, `upd_fail` | P2 | `oldest_created` now served by B-1 |
| **B-5** | Drop index `idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)` | same | `count_partitions`, `count_partition_single`, `keys_*`, `oldest_*`; writes | P1, P2 | Revert to restore partition-leading ranges |
| **B-6** | Drop index `idx_outbox_record_status_retry (status, next_retry_at)` | same | `count_new/failed/completed`, `oldest_due_*`; retention failed queries (P3) | P1, P2 (and P3) | |
| **B-7** | Drop index `idx_outbox_record_status_created (status, created_at)` | same | `keys_*`, `oldest_created`; P3 `find_pending_all`, `find_failed_all` | P1, P2 (and P3) | |
| **B-8** | Drop index `idx_outbox_record_record_key_created (record_key, created_at)` | same | `keys_*` (subquery), `pages`, P3 `find_incomplete_by_key` | P1 (and P3) | Subsumed by B-2 |
| **B-9** | Drop index `idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` | same | same as B-8 | P1 | Replaced by B-2 (same prefix + `id`) |
| **B-10** | `keys_strict` rewritten: subquery alias `earlier` (with `earlier.completed_at IS NULL`, `earlier.created_at <= o.created_at` range); `ORDER BY o.created_at, o.id LIMIT :limit`; `withMaxRows(batchSize)` added at the call site (B-18). Outer `o.completed_at IS NULL` is absent at 270c42cf (present at 2a2663de). `LIMIT` was already in `main` | `query/DefaultJdbcOutboxRecordQueries.kt` (`findStrictRecordKeys`, `noEarlierIncompleteRecord`), `query/MySqlJdbcOutboxRecordQueries.kt`, `JdbcOutboxRecordRepository.kt:206` | `keys_strict` | P1 | Needs B-1/B-2 to benefit |
| **B-11** | `keys_nonstrict` rewritten from `GROUP BY o.record_key ORDER BY MIN(o.created_at) LIMIT` to `NOT EXISTS` of an earlier due NEW record of the same key (`earlier.completed_at IS NULL`), `ORDER BY o.created_at, o.id LIMIT :limit`; no outer `completed_at IS NULL` at 270c42cf | same | `keys_nonstrict` | P1 | Different statement shape; result key order the same intent (oldest due record per key) |
| **B-12** | `pages`: `AND completed_at IS NULL` added | `DefaultJdbcOutboxRecordQueries.kt` (`findIncompleteByKeysAndStatus`) | `pages` | P1 | Needs B-2 |
| **B-13** | `oldest_created` rewritten from `SELECT MIN(created_at) ... WHERE partition_no IN AND status` to `SELECT created_at ... WHERE partition_no IN AND status ORDER BY created_at LIMIT :limit` (no `completed_at IS NULL` at 270c42cf); call binds `limit` 1 and `withMaxRows(1)`; status fixed to `NEW` in the call | `DefaultJdbcOutboxRecordQueries.kt` (`oldestCreatedAtByPartitionsStatus`), repository `:306-314` | `oldest_created` | P2 | Needs B-1 |
| **B-14** | `oldest_due_strict`: `SELECT MIN(o.next_retry_at)` replaced by `SELECT o.next_retry_at ... ORDER BY o.next_retry_at LIMIT :limit` (limit 1) with `earlier` subquery (`earlier.completed_at IS NULL`); no outer `completed_at IS NULL` at 270c42cf; `withMaxRows(1)` | `DefaultJdbcOutboxRecordQueries.kt` (`oldestDueStrict`), repository `:321` | `oldest_due_strict` | P2 | Needs B-3 |
| **B-15** | `oldest_due_nonstrict`: `SELECT MIN(next_retry_at)` replaced by `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT :limit` (no `completed_at IS NULL` at 270c42cf); call applies `withMaxRows(1)` and binds `limit` (now used by the SQL) | same (`oldestDueNonStrict`) | `oldest_due_nonstrict` | P2 | Needs B-3 |
| **B-16** | New method `countByStatuses`: one `SELECT status, COUNT(*) ... WHERE status IN (:statuses) GROUP BY status`; the `OutboxRecordRepository` interface gains a default implementation that loops `countByStatus` | `OutboxRecordRepository.kt`, `DefaultJdbcOutboxRecordQueries.kt`, `JdbcOutboxRecordRepository.kt:251` | `count_statuses`, `count_statuses_all` | P2 | Used only by the observability collector |
| **B-17** | Metrics collector counts only `record-statuses` (default `{NEW, FAILED}`) with one grouped query instead of three `countByStatus` including `COMPLETED` | `OutboxMetricsCollector.kt`, `OutboxMetricsProperties.kt` | `count_new`, `count_failed`, `count_completed` (collector no longer issues `count_completed` by default), `count_statuses` | P2 | Depends on B-16 |
| **B-18** | `withMaxRows(batchSize)` added to `findRecordKeysInPartitions` call (the `LIMIT :limit` of `main` stays) | `JdbcOutboxRecordRepository.kt:226` | `keys_strict`, `keys_nonstrict` | P1 | Client-side only on Connector/J 9.7.0 (section 4) |
| **B-19** | Dialect layer: `JdbcOutboxRecordQueries`/`DefaultJdbcOutboxRecordQueries`/`MySqlJdbcOutboxRecordQueries` classes and `JdbcOutboxRecordQueriesFactory` replace `rowLimitClause` on `JdbcDatabaseType`; `JdbcDatabaseType.detect` returns `null` for unknown products. MySQL SQL has no additional statement | `query/*.kt`, `config/JdbcDatabaseType.kt`, `JdbcOutboxPersistenceFactory.kt`, `config/JdbcOutboxAutoConfiguration.kt` | all (structural) | P1, P2 | No SQL effect of its own |
| **B-20** | API: `status` parameter removed from `findRecordKeysInPartitions`, `findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions`; the SQL still binds `NEW` | `OutboxRecordRepository.kt`, `OutboxProcessingScheduler.kt:163`, collector | `keys_*`, `oldest_*` | P1, P2 | No SQL effect |
| **B-21** | P3, not measured: retention listings/id selects (`find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before`) gain `LIMIT :limit` (plus the existing `withMaxRows(limit)`) | `DefaultJdbcOutboxRecordQueries.kt` | RET ids | P3, not measured | |
| **B-22** | P3, not measured: `find_incomplete_by_key` gains `AND completed_at IS NULL` | same (`findIncompleteByKeyAndStatus`) | `find_incomplete_by_key` | P3, not measured | |

Unchanged statements: `insert`, `upd_complete`, `upd_retry`, `upd_fail`, `delete_by_id` (text identical, formatting only); `count_new`, `count_failed`,
`count_completed`, `count_partitions`, `count_partition_single` (SQL unchanged, served by different indexes); `find_pending_all`,
`find_failed_all`, `delete_by_ids`, `delete_by_status_failed`, `delete_by_key_status` (unchanged).

Other differences outside MySQL: PostgreSQL/Oracle/SQL Server/H2/MariaDB DDL and classes, JPA repository, MongoDB repository, tests, docs
(not analysed).

## 8. Open questions for the orchestrator

1. `02-scenarios.md` "Constraints" says `main` limits rows via `Statement.setMaxRows`. In the `main` code only the P3 retention queries do;
   its MySQL key queries use `LIMIT :limit` (the dialect `rowLimitClause`). The catalog headers already say `maxrows: none` for `main` keys;
   the SQL runner must send `LIMIT` for the key queries on both variants and reproduce `setMaxRows` only on `opt` (batchSize, 1) - confirm.
2. B-18 (`withMaxRows` + `LIMIT` on the key query): with Connector/J 9.7.0 it is client-side only and has no server effect; no timing
   difference is expected apart from the driver call; decide whether to run it as its own variant or fold it into B-10/B-11.
3. Should B-5..B-9 (index drops) be evaluated one index at a time or as one DDL change (B-1..B-9 form one schema set)? The writecost
   measurement per index is a natural unit; the read effect needs all dependent statements.
4. MariaDB through Connector/J gets MySQL SQL and DDL (section 3). Should the final report state that results are only valid for the MySQL
   server, and that MariaDB-through-MySQL-driver deployments need the MariaDB study's confirmation?
5. TIMESTAMP caveat on MariaDB (section 3, point 2): out of scope for the MySQL study; mention only if relevant to the final report.
> **CORRECTION 2026-11-06 (re-pin to 270c42cf):** index renamed at 270c42cf to `idx_outbox_record_status_created_id` (no `completed_at`, so the entry is about 8 bytes smaller; still ~2.2 KB).

6. `idx_outbox_record_status_completed_created_id` max entry size about 2.2 KB (< 3072); if a user changes column lengths (e.g. longer
   `record_key`) it can approach the limit. Add a DDL check? Not tested.
7. Both variants keep `created_at TIMESTAMP` second precision; the opt NOT EXISTS predicates rely on `id` tie-break; the 2 % tie rate
   in the scenarios exercises it. No change requested.
