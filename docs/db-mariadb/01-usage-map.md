# 01 - `outbox_record` usage map for MariaDB

Sources: `main` worktree `/home/azamojski/outbox-wt-mdb-main` (f4446c8c, variant `mdb-main`), branch worktree
`/home/azamojski/outbox-wt-mdb-opt` (a06a74c0, variant `mdb-opt`). Code, DDL and tests only (plus the ADR for the P1/P2/P3
grouping). Resolved SQL was cross-checked against `docs/db-mariadb/harness/catalog/{mdb-main,mdb-opt}/mariadb/*.sql`:
**no disagreement** on any of the 26 / 28 statements. No `EXPLAIN` was run; every "likely access path" below is a prediction
to be confirmed by the harness.

Paths below are relative to the worktree root. `JdbcRepo` = `namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/JdbcOutboxRecordRepository.kt`.

## 1. Table on MariaDB

DDL: `namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql`. Columns are **identical** on `main` and the branch;
only the secondary indexes differ.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `VARCHAR(255)` | NOT NULL | **PRIMARY KEY** (clustered). UUID string in practice (36 chars). |
| `status` | `VARCHAR(20)` | NOT NULL | `NEW`, `COMPLETED`, `FAILED` (string name of the enum). |
| `record_key` | `VARCHAR(255)` | NOT NULL | Storage key (may include an encoded processing group). |
| `record_type` | `VARCHAR(255)` | NOT NULL | |
| `payload` | `LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` | NOT NULL | |
| `context` | `LONGTEXT ... utf8mb4_unicode_ci` | NULL (default NULL) | |
| `created_at` | `TIMESTAMP` | NOT NULL | second precision |
| `completed_at` | `TIMESTAMP DEFAULT NULL` | NULL | |
| `failure_count` | `INT` | NOT NULL | |
| `failure_reason` | `VARCHAR(1000)` | NULL | |
| `next_retry_at` | `TIMESTAMP` | NOT NULL | second precision |
| `partition_no` | `INT` | NOT NULL | 0..255 |
| `handler_id` | `VARCHAR(1000)` | NOT NULL | |

Table: `ENGINE = InnoDB`, **no table-level `CHARACTER SET`/`COLLATE`**: the non-LONGTEXT string columns (`id`, `status`,
`record_key`, `record_type`, `failure_reason`, `handler_id`) use the schema/server default charset and collation (the default
depends on the MariaDB version: `latin1`/`utf8mb4` and a `*_general_ci` / `*_uca1400_ai_ci` style collation). Only `payload` and
`context` are pinned. Consequence for the study: key lengths of `id`/`record_key` depend on the container's default charset
(1 vs 4 bytes per char); record it in the harness environment notes. Equality on those columns is case-insensitive under the
default collations.

Indexes:

| `main` | columns |
|---|---|
| PK | `(id)` |
| `idx_outbox_record_record_key_created` | `(record_key, created_at)` |
| `idx_outbox_record_partition_status_retry` | `(partition_no, status, next_retry_at)` |
| `idx_outbox_record_status_retry` | `(status, next_retry_at)` |
| `idx_outbox_record_status_created` | `(status, created_at)` |
| `idx_outbox_record_record_key_completed_created` | `(record_key, completed_at, created_at)` |

| branch | columns |
|---|---|
| PK | `(id)` (unchanged) |
| `idx_outbox_record_status_completed_created_id` (call it **B-idx1**) | `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` |
| `idx_outbox_record_key_completed_created_id` (**B-idx2**) | `(record_key, completed_at, created_at, id)` |
| `idx_outbox_record_status_completed_retry` (**B-idx3**) | `(status, completed_at, next_retry_at, partition_no)` |
| `idx_outbox_record_status_completed_partition` (**B-idx4**) | `(status, completed_at, partition_no)` |

Main has 5 secondary indexes, the branch 4. No constraints other than the PK (no foreign keys, no CHECK, no UNIQUE).
`outbox_instance` / `outbox_partition` DDL is unchanged and out of scope. The MySQL DDL (`schema/mysql`) carries the same record
indexes on the branch and differs only in cosmetic `DEFAULT NULL` clauses.

InnoDB consequences (general knowledge):

- The table is a clustered B+tree on `id VARCHAR(255)`. UUIDv4 ids give random insert positions into the clustered index and
  page splits; the PK is also the lookup path of every `UPDATE ... WHERE id` and `DELETE ... WHERE id`.
- Every secondary index entry stores the index columns **plus the PK columns not already in the key** (here `id`). A
  secondary entry is therefore at least ~36+ bytes bigger than the key; B-idx1 and B-idx2 already contain `id`, so it is not
  appended twice. A non-covering lookup costs one extra clustered-index probe per row.
- `TIMESTAMP` is 4 bytes at second precision (no fractional part): `created_at` ties inside one key are frequent when several
  records are scheduled in the same second, so the `id` tie-break decides order; B-idx1/B-idx2 carry `id` after `created_at`, main's
  indexes do not.
- Key length limit: InnoDB (DYNAMIC row format) allows 3072 bytes per index key (and 767 for REDUNDANT/COMPACT in old servers).
  Under utf8mb4 `VARCHAR(255)` counts up to 1020 bytes. Worst case B-idx1 = `status` 80 + `completed_at` 5 + `created_at` 4 +
  `id` 1020 + `partition_no` 4 + `next_retry_at` 4 + `record_key` 1020 (+ length bytes) ≈ 2.15 KB < 3072, so the DDL is accepted;
  with realistic data (36-char key and id) an entry is ~100 bytes.
- **Hazard (verify on the lane's server version):** `created_at TIMESTAMP NOT NULL` is the first TIMESTAMP column. On MariaDB
  < 10.10, or whenever `explicit_defaults_for_timestamp=OFF`, that column implicitly gets
  `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`; then `updateProcessingState` (which does not set `created_at`) would
  rewrite `created_at` and break per-key ordering. Since 10.10 the default is ON. The same applies to both variants.

## 2. Field semantics and invariants

Source: `namastack-outbox-core/.../OutboxRecord.kt` (`markCompleted` l.111, `markFailed` l.122, `canBeRetried` l.141,
`scheduleNextRetry` l.156), `OutboxService.kt:479-500`, processors in `.../processor/`.

- `id`: random UUID; only a tie-breaker. `record_key`: storage key; **order within a key is `(created_at, id)` ascending**.
- `created_at`: set once at scheduling (`clock.instant()` in `OutboxService`), never updated by `updateProcessingState`.
- `partition_no`: `PartitionHasher.getPartitionForRecordKey(record_key)` = MurmurHash3 x86-32 (seed 0) of the UTF-8 key bytes,
  `& 0x7fffffff`, `% 256` (`TOTAL_PARTITIONS = 256`, never changed). Set at `create`, never changed. Instances own a subset
  of the 256 partitions; the key query filters `partition_no IN (:owned)`.
- `handler_id`: stable handler identity; one record is written per matching handler; used to route/retry-policy at processing time.
  Not used in any predicate.
- `status`: `NEW` on create (`OutboxService.create`). `COMPLETED` set by `markCompleted` (also sets `completed_at`) in
  `completeRecord` (`OutboxRecordProcessor.kt:62-80`) after a successful handler invocation, unless
  `processing.deleteCompletedRecords=true` in which case the row is deleted instead. `FAILED` set by `markFailed` in
  `PermanentFailureOutboxRecordProcessor.kt:30` after retries are exhausted or the failure is not retryable and the fallback did
  not complete the record.
- `failure_count`, `failure_reason`: incremented/overwritten on each failed delivery (retry or fail path), written by
  `updateProcessingState`.
- `next_retry_at`: `= created_at` on create (due immediately); `now + backoff` after each failed attempt
  (`RetryOutboxRecordProcessor.kt:41-43`); left unchanged on complete and on permanent failure (so for COMPLETED it is the last
  schedule, for FAILED the last attempt; retention queries use it for FAILED).
- `completed_at`: NULL on create; set only on completion. **Invariant: `completed_at IS NULL` <=> status is `NEW` or `FAILED`.**
  The branch relies on it (documented in `JdbcOutboxRecordQueries`); main's statements do not use it except inside the
  predecessor sub-select.
- A record is processable when `status = NEW AND next_retry_at <= now`; the processing loop skips non-due records and, in strict
  mode (`stopOnFirstFailure=true`, default), stops a key at the first record that did not complete.
- Strict key eligibility: a `NEW` due record `o` is eligible iff no record of the same key with `completed_at IS NULL` (NEW or
  FAILED, due or not) precedes it in `(created_at, id)`. Non-strict: any key with a due `NEW` record.

```
              create                 handler ok
  (none) ----------------> NEW ---------------------> COMPLETED   (completed_at set)
                            |  ^                       (or row DELETEd when delete-on-complete)
        handler fails,      |  | retry scheduled:
        retries left        |  | next_retry_at = now+delay, failure_count++
                            +--+
                            | retries exhausted / not retryable (fallback did not complete)
                            v
                          FAILED       (completed_at stays NULL; terminal, kept until retention/admin delete)
```

## 3. Dialect detection

**Branch.** `JdbcOutboxAutoConfiguration` (l.122-126) and `JdbcOutboxPersistenceFactory` (l.50-54) pass a lazy supplier
`JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.detect(dataSource), tableName)` to `JdbcOutboxRecordRepository`
(`private val sql by lazy(queries)`; first statement opens one connection or reuses the transactional one).

- `JdbcDatabaseType.detect` (`config/JdbcDatabaseType.kt`, new): `JdbcUtils.extractDatabaseMetaData(ds, getDatabaseProductName)` ->
  `JdbcUtils.commonDatabaseName` -> `from(name)` inside `runCatching`; any unsupported name -> `null`.
- `from(name)`: `lowercase()` map: `postgresql`, `mysql`, `h2`, `mariadb`, `microsoft sql server`, `oracle`; else
  `IllegalArgumentException`. So product name **`MariaDB` -> `JdbcDatabaseType.MariaDB` -> `MariaDbJdbcOutboxRecordQueries`**;
  **`MySQL` -> `MySqlJdbcOutboxRecordQueries`**; `null` -> `DefaultJdbcOutboxRecordQueries` (no `LIMIT` clause, driver `setMaxRows` only).
- `MariaDbJdbcOutboxRecordQueries` and `MySqlJdbcOutboxRecordQueries` are both `DefaultJdbcOutboxRecordQueries(table, "LIMIT :limit")`
  with **no overrides**: byte-identical SQL. Choosing "MySQL class" vs "MariaDB class" therefore does not change MariaDB
  results today. `DefaultJdbcOutboxRecordQueries` (unknown product) differs only by rendering no `LIMIT`; it still gets
  `setMaxRows`.

What the driver reports (MariaDB Connector/J 3.5.10 bytecode, `org.mariadb.jdbc.DatabaseMetaData.getDatabaseProductName()`):
returns `"MariaDB"` iff `!conf.useMysqlMetadata() && serverVersion.isMariaDBServer()`, else `"MySQL"`. So:

- MariaDB server via `jdbc:mariadb:` and MariaDB driver: `MariaDB` (MariaDB class).
- MariaDB server with the MariaDB driver and `useMysqlMetadata=true`: `MySQL` (MySQL class, same SQL; but DDL file choice also flips).
- MariaDB server reached through **MySQL Connector/J** (`jdbc:mysql:`): Connector/J reports `MySQL`, so the **MySQL class and
  `schema/mysql` DDL** are selected. (Reported as general knowledge of mysql-connector-j; not decompiled.) Same SQL strings today.
- MySQL server through the MariaDB driver: product name `MySQL`.
- Non-MariaDB/MySQL/other known products and unsupported names: default statements; no failure on the record path.

**`main`.** `JdbcOutboxRecordRepository` takes `databaseType: () -> JdbcDatabaseType`, resolved lazily from
`JdbcDatabaseType.from(dataSource)` (throws `RuntimeException` if undetectable or unsupported); the only dialect-dependent text is
`rowLimitClause`, which is `LIMIT :limit` for `MySQL` and `MariaDB` and `FETCH FIRST :limit ROWS ONLY` for the others. All other SQL is
shared. Hence on `main` MariaDB uses `LIMIT :limit` only on the two key queries (the other limited queries have **no limit clause** in
`main`; they use `withMaxRows` only).

Unchanged on both: the schema initializer (`JdbcOutboxSchemaAutoConfiguration.kt:46`) still calls the throwing `from(dataSource)`
to pick the DDL file, so a MariaDB server behind the MySQL driver creates the MySQL-DDL table.

**Finding: MariaDB can end up with MySQL-class statements (MySQL driver or `useMysqlMetadata=true`) and with the default class never
(a MariaDB product name is always mapped), but today all three classes render the same SQL for MariaDB except that the default class
omits `LIMIT`.** For the harness: it must use the MariaDB driver (`mdb-*` runs measure `MariaDbJdbcOutboxRecordQueries`).

## 4. Method catalog

Notation: P = `:partitions` (list of owned partition numbers, `IN (...)`), `now` = clock instant. Callers are on the branch;
`main` callers have the same files with the removed `status` argument. "max-rows" = `Statement.setMaxRows`.

### MariaDB Connector/J `setMaxRows` (3.5.10, decompiled `org.mariadb.jdbc.Statement`/`Connection`)

`Connection.canUseServerMaxRows` is true iff the server is MariaDB >= 10.3.0 (no user option). When true and `maxRows > 0`, the driver
rewrites the statement text to **`SET STATEMENT SQL_SELECT_LIMIT=<n> FOR <sql>`** (combined with `max_statement_time` when a query
timeout is set). So against MariaDB, `withMaxRows(n)` is a **server-side** limit, not client-side truncation. `SQL_SELECT_LIMIT` is
treated by the optimizer like a `LIMIT` on the top-level SELECT (general knowledge: it does not apply to sub-selects; assumed to enable
the same early-stop/`ORDER BY ... LIMIT` plan; **to be confirmed by `EXPLAIN`/`SHOW STATUS` in the harness**). On the branch, statements
with `LIMIT :limit` additionally get this prefix with the same value; harmless duplication. With a MySQL driver the behaviour is
**unverified** (not decompiled). The harness note says `main` "limits rows via `setMaxRows`": for MariaDB this is the `SET STATEMENT`
rewrite, so a runner that only uses plain JDBC `setMaxRows` against the MariaDB driver reproduces it, but a runner that executes
plain SQL without the driver rewrite would not (check `useServerMaxRows` equivalence).

Frequency anchors: fixed polling default `interval = 2 s` (`OutboxProperties.FixedPolling`), adaptive optional; `polling.batchSize`
default 10; `MAX_RECORD_KEYS_PER_FETCH = 1000`; observability metrics refresh default 15 s (`OutboxMetricsProperties`).

SQL below shows `<rl>` = `LIMIT :limit` where the branch renders it.

### P1 - scheduling and execution

**`findRecordKeysInPartitions`** (strict). Purpose: keys with a due `NEW` record that is the first incomplete record of its key.
Caller `OutboxProcessingScheduler.kt:164` (`loadRecordKeys`), once per poll, inside `partitionCoordinator.withStableAssignments`
(`:147`) and only when owned partitions are non-empty; runs every poll even when idle. Enabled always;
strict is `processing.stopOnFirstFailure=true` (default). Params: P, `status='NEW'`, `now`, `limit=batchSize` (10). Result <= batchSize
strings. No explicit transaction (auto-commit read; the scheduler wraps only the assignment lock, not a DB transaction).
- main (id `keys_strict`; `LIMIT :limit`, no max-rows):
  `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id))) ORDER BY o.created_at ASC, o.id ASC LIMIT :limit`
- branch (`keys_strict`; `LIMIT :limit` + max-rows batchSize):
  `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id))) ORDER BY o.created_at, o.id LIMIT :limit`
- Indexes. main: outer via `idx_..._partition_status_retry (partition_no, status, next_retry_at)` range per partition (P is a multi-range
  IN list; rows then need a filesort on `(created_at, id)` because no index yields that order, so the early stop of LIMIT is lost; the
  engine reads all due rows of owned partitions, probes the sub-select, sorts); sub-select via `idx_..._record_key_completed_created
  (record_key, completed_at, created_at)` (`completed_at IS NULL` ref range + created_at filter) or `record_key_created`. Branch: outer likely
  B-idx1 `(status, completed_at, created_at, id, ...)` with `status='NEW' AND completed_at IS NULL` ref prefix, scanned in
  `(created_at, id)` order with covering columns `partition_no`, `next_retry_at`, `record_key` (covering; no PK probe), the
  partition/due predicates applied as index-condition filters, and LIMIT stops early; sub-select via B-idx2 `(record_key, completed_at,
  created_at, id)` range. Planner choice must be verified (`partition_no IN` list vs ordered scan trade-off; with 64/16 owned partitions of 256 many
  scanned rows are rejected before the limit is reached).

**`findRecordKeysInPartitions`** (non-strict, `stopOnFirstFailure=false`). Same caller/frequency.
- main (`keys_nonstrict`; `LIMIT :limit`): `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC LIMIT :limit`
- branch (`keys_nonstrict`; `LIMIT :limit` + max-rows): `SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.status = :status AND earlier.next_retry_at <= :now AND earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id))) ORDER BY o.created_at, o.id LIMIT :limit`
- Semantics differ slightly: main groups all due rows per key and orders keys by the key's earliest due `created_at` (full GROUP BY +
  sort; likely a temporary table); branch emits only the first due record of each key, ordered by `(created_at, id)` (the same set of
  keys; ties between keys ordered by `id`). Indexes: main `partition_status_retry`; branch B-idx1 outer, B-idx2 sub-select.

**`findIncompleteRecordPages`**. Purpose: bulk-load the `NEW` records of the selected keys. Caller `OutboxProcessingScheduler.kt:174`,
per poll that found keys, once per chunk of <=1000 keys (default batch 10 -> one call). Params: set of keys (<= batchSize; S4 also 100),
`status='NEW'`. Result: all `NEW` rows of those keys including payload/context (SELECT *), ~1..9 rows per key (S5 hot keys: up to
2000 per key). No explicit transaction. Rows are grouped client-side per key.
- main (`pages`): `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id`
- branch (`pages`): `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id`
- Indexes. main: `record_key_completed_created (record_key, completed_at, created_at)` or `record_key_created`; per-key range then PK
  probe per row (`status` is filtered after the PK lookup because no index has `record_key, status`); `id` tie-break needs a sort or
  not guaranteed by the index. Branch: B-idx2 `(record_key, completed_at, created_at, id)`: for each key `completed_at IS NULL` ref range
  delivers `created_at, id` order (and `record_key` first, so `ORDER BY record_key, created_at, id` can be satisfied by the multi-range
  scan? the multi-range IN may still need a filesort; verify), then PK probe for non-index columns, with `status` filtered after the probe
  (it is NOT in B-idx2; FAILED rows of the key pass the index range and are discarded after the PK probe).
- Note `findIncompleteRecordsByRecordKey` (single key, see P3) has the same predicate.

**`create`** (`OutboxService.kt:500`, per scheduled record, in the caller's business transaction). Branch/main identical SQL (`insert`):
`INSERT INTO outbox_record (id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id) VALUES (...)`. One clustered insert + all secondary indexes.
`save`/`update` (full-row `UPDATE ... WHERE id`) are not called by core on the hot path (no caller found in main sources).

**`updateProcessingState`** (callers `RetryOutboxRecordProcessor.kt:43`, `PermanentFailureOutboxRecordProcessor.kt:31`,
`OutboxRecordProcessor.kt:73`): once per processed record outcome (complete / retry / fail). Own `TransactionTemplate` transaction,
single-row PK update (row lock on the clustered record). Identical on both (ids `upd_complete`, `upd_retry`, `upd_fail`):
`UPDATE outbox_record SET status = :status, completed_at = :completedAt, failure_count = :failureCount, failure_reason = :failureReason, next_retry_at = :nextRetryAt WHERE id = :id`. Only the PK serves it; secondary indexes only cost maintenance (section 6).

**`deleteById`** (`OutboxRecordProcessor.kt:66`): per record when `deleteCompletedRecords=true` (delete-on-complete). Own transaction.
Identical: `DELETE FROM outbox_record WHERE id = :id` (PK, then delete-mark in every secondary index).

### P2 - metrics collector (module `namastack-outbox-observability`, `OutboxMetricsCollector`)

Dedicated scheduler, `scheduleWithFixedDelay(refresh, 15 s)`; one refresh = record counts + partition metrics. Queries run on
a pooled connection outside any transaction; they compete with P1 for buffer pool and CPU. Callers at `OutboxMetricsCollector.kt`
:152 (`countByStatuses`), :181, :189, :199. Partition metrics run only when the instance owns partitions; the `P` set is the owned
partitions.

**Record counts.** main: `countByStatus(status)` is called for **each of NEW, FAILED, COMPLETED** (3 queries per refresh, ids
`count_new`, `count_failed`, `count_completed`): `SELECT COUNT(*) FROM outbox_record WHERE status = :status`. Branch: one
`countByStatuses(recordStatuses)`, default statuses **{NEW, FAILED}** (`OutboxMetricsProperties.recordStatuses`; `COMPLETED` only if the user
configures it), ids `count_statuses` (NEW+FAILED) / `count_statuses_all` (all three, optional):
`SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status`. Result <= 3 rows. Index: main `status_retry`
or `status_created` (index-only range count; COMPLETED scan of 98 % of the table in S2); branch B-idx1/B-idx3/B-idx4 (all start with
`status`; B-idx4 is the narrowest; for `IN` of NEW and FAILED a loose range scan over each status; the COMPLETED count remains a large scan).
The deprecated `namastack-outbox-metrics` module still calls `countByStatus` per gauge scrape (`OutboxRecordMetricsMeterBinder.kt:36`)
and `countRecordsByPartition` per partition (`OutboxPartitionMetricsProvider.kt:38`); not a target (ids `count_partition_single`).

**`countRecordsByPartitions`** (id `count_partitions`), identical on both: `SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status` (`NEW`). Main: `partition_status_retry (partition_no, status, ...)` index-only range per partition. Branch: B-idx1/B-idx4 `(status, completed_at, partition_no)`... with `status='NEW'` but no `completed_at` predicate, so a
range on `status` prefix then partition filter inside the index (index-only on B-idx4/B-idx3, no skip); all `NEW` rows (retry-waiting included) are scanned.

**`findOldestCreatedAtByPartitions`** (id `oldest_created`): oldest `created_at` of `NEW`.
- main (no limit, no max-rows): `SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status`
- branch (`LIMIT :limit`, limit 1, max-rows 1): `SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT :limit`
- Indexes: main `partition_status_retry` per partition (scans all NEW rows of the partitions, PK probe for `created_at`; `status_created`
  would serve `MIN(created_at)` if `partition_no` were not filtered); branch B-idx1 `status='NEW', completed_at IS NULL` ordered by `created_at` with
  `partition_no` in the index: stops at the first row whose partition is owned (cheap if owned partitions are dense).

**`findOldestDueNextRetryAtByPartitions`** strict (id `oldest_due_strict`), caller `:199`, when `stopOnFirstFailure`:
- main: `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))`
- branch (`LIMIT :limit`=1, max-rows 1): `SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id))) ORDER BY o.next_retry_at LIMIT :limit`
- Main evaluates the NOT EXISTS for every due row (no early stop); branch orders by `next_retry_at` so B-idx3 `(status, completed_at, next_retry_at, partition_no)` can deliver the first row that passes.

**`findOldestDueNextRetryAtByPartitions`** non-strict (id `oldest_due_nonstrict`):
main `SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now`; branch adds `AND o.completed_at IS NULL` (max-rows 1; `MIN()` returns one row).
Indexes: main `partition_status_retry` range; branch B-idx3 `(status, completed_at, next_retry_at, partition_no)` min via first index row.

### P3 - retention and administration (not timed; correctness only)

All public repository/actuator methods, **caller-driven** (no scheduled caller in this repo's sources; user code or ADR-0003 retention
tooling calls them): `deleteCompletedRecords`/`deleteFailedRecords` (retention jobs, batches of `limit`), `findCompletedRecords(before, limit)`,
`findFailedRecords(before, limit)`, `findPendingRecords`/`findCompletedRecords()`/`findFailedRecords()` (unbounded listings), `deleteByStatus`/
`deleteByRecordKeyAndStatus` (actuator `OutboxActuatorEndpoint.kt:33,45`), `deleteByIds`, `findIncompleteRecordsByRecordKey` (no core
caller found; public API).

| id | main SQL | branch SQL (diff) |
|---|---|---|
| `find_completed_before` | `SELECT * ... WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at` (max-rows limit, **no `LIMIT`**) | same + ` LIMIT :limit` (+max-rows) |
| `find_failed_before` | `... WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at` (max-rows) | same + ` LIMIT :limit` |
| `ids_completed_before` | `SELECT id ... status/completed_at ... ORDER BY completed_at` (max-rows) | same + ` LIMIT :limit` |
| `ids_failed_before` | `SELECT id ... status/next_retry_at ... ORDER BY next_retry_at` (max-rows) | same + ` LIMIT :limit` |
| `delete_by_ids` | `DELETE FROM outbox_record WHERE id IN (:ids)` (<= limit ids, e.g. 1000) | identical |
| `delete_by_status_failed` | `DELETE FROM outbox_record WHERE status = :status` | identical |
| `delete_by_key_status` | `DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status` | identical |
| `find_pending_all`, `find_failed_all` | `SELECT * ... WHERE status = :status ORDER BY created_at, id` (unbounded) | identical |
| `find_incomplete_by_key` | `SELECT * ... WHERE record_key = :recordKey AND status = :status ORDER BY created_at, id` | adds `AND completed_at IS NULL` |

`deleteCompletedRecords`/`deleteFailedRecords` = one transaction: id select (above) + `deleteByIds`. Retention order uses
`completed_at` (COMPLETED) or `next_retry_at` (FAILED). Likely paths: main `status_created`/`status_retry` (no index has
`(status, completed_at)`: COMPLETED retention sorts by `completed_at` after scanning the status range, filesort); branch
B-idx1/B-idx3 `(status, completed_at, ...)` give `completed_at` order directly (COMPLETED); FAILED `next_retry_at` order: B-idx3 has
`completed_at` between `status` and `next_retry_at` (with `completed_at IS NULL` not in the predicate, so no ordered range; likely filesort
within FAILED rows). The `delete_by_key_status` path: main `record_key_*` ref + PK probe; branch B-idx2 ref (status filtered after probe).

## 5. Priority grouping (ADR-0005)

| Group | Methods | Frequency | Why it matters |
|---|---|---|---|
| **P1** scheduling/execution | `findRecordKeysInPartitions` (strict, non-strict), `findIncompleteRecordPages`, `create`, `updateProcessingState` (complete, retry, fail), `deleteById` | key query: once per poll per instance (default every 2 s, even idle); pages: once per poll with keys; writes: once per record state change (complete/retry/fail) and per insert | Determines delivery latency and throughput; writes add index maintenance on every record lifecycle step. ADR: ranks partial-ownership results above full ownership. |
| **P2** metrics | `countByStatuses` (NEW, FAILED), `countRecordsByPartitions`, `findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions` | 6 queries per refresh (3 counts + pending + oldest + overdue) every 15 s per instance on `main`; 4 on the branch with default statuses | Operator-visible health; runs on the same DB and must not scan the retained COMPLETED history. |
| **P3** retention/admin | `find*Records`, `delete*Records`, `deleteByIds`, `deleteByStatus`, `deleteByRecordKeyAndStatus`, `findIncompleteRecordsByRecordKey`, `countByStatus`/`countRecordsByPartition` (deprecated metrics module) | caller-driven / manual; retention batches are periodic user jobs | Non-goal for speed; must stay correct. |

## 6. Write amplification on InnoDB

Only index entries whose key columns **actually change** are touched (InnoDB delete-marks the old entry and inserts a new one; an
unchanged indexed column causes no secondary-index work). The `UPDATE` always rewrites the clustered record (`failure_count`,
`failure_reason`, `next_retry_at`, `status`, `completed_at` are in the clustered row; undo + redo for the changed columns).
Columns of `updateProcessingState` that change per outcome (in-memory record state):

| operation | columns changed | `main` secondary indexes maintained (of 5) | branch secondary indexes maintained (of 4) |
|---|---|---|---|
| `create` | all (new row) | all 5 inserts | all 4 inserts (B-idx1 widest) |
| `upd_complete` | `status` NEW->COMPLETED, `completed_at` NULL->ts (`failure_count`, `failure_reason`, `next_retry_at` normally unchanged) | 4: `partition_status_retry`, `status_retry`, `status_created` (via `status`), `record_key_completed_created` (via `completed_at`); `record_key_created` untouched | 4: all (B-idx1 `status, completed_at`; B-idx2 `completed_at`; B-idx3; B-idx4) |
| `upd_retry` | `next_retry_at`, `failure_count`, `failure_reason` (status stays NEW, `completed_at` NULL) | 2: `partition_status_retry`, `status_retry` | 2: B-idx1, B-idx3 (they carry `next_retry_at`) |
| `upd_fail` | `status` NEW->FAILED, `failure_count`/`failure_reason` (`completed_at` stays NULL, `next_retry_at` unchanged by `markFailed`) | 3: `partition_status_retry`, `status_retry`, `status_created` | 3: B-idx1, B-idx3, B-idx4 (B-idx2 untouched) |
| `deleteById` | row removed | delete-mark in all 5 + clustered | delete-mark in all 4 + clustered |

Branch B-idx1 entries are the widest (record_key and id plus 4 other columns), so each touched B-idx1 entry costs more redo/undo and
page churn than main's narrow entries; delete-marked entries are purged asynchronously (history list length, relevant to S9). The branch
has one secondary index fewer (4 vs 5) and touches the same number of indexes on complete (4), retry (2) and fail (3); on complete
every branch index is touched (all four contain `status` or `completed_at`), while main's `record_key_created` is not.

## 7. Branch change list (`git diff f4446c8c..a06a74c0 -- . ':!docs'`)

Each item is independently revertible (query ids from `02-scenarios.md`). JPA/Mongo/H2/Postgres/Oracle/SQL Server changes are
context only and not listed; so are tests.

- **B-1 - Drop and replace record indexes (DDL).** Removes `idx_outbox_record_record_key_created`, `..._partition_status_retry`,
  `..._status_retry`, `..._status_created`, `..._record_key_completed_created`; adds B-idx1..B-idx4 (all four are introduced
  together here; revert units can be split by index: **B-1a** idx1, **B-1b** idx2, **B-1c** idx3, **B-1d** idx4). Files:
  `namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql` (the MySQL DDL carries the same change). Affects all ids
  (plan choice) and the WRITE group.
- **B-2 - Key query: `o.completed_at IS NULL` + predecessor sub-select with `created_at <=` range, `earlier` alias, ORDER BY without ASC
  (strict).** Files: `DefaultJdbcOutboxRecordQueries.kt` (`findStrictRecordKeys`, `noEarlierIncompleteRecord`). Ids: `keys_strict`.
  Also adds `withMaxRows(batchSize)` next to `LIMIT :limit` (see B-8).
- **B-3 - Non-strict key query rewritten from `GROUP BY record_key ORDER BY MIN(created_at)` to first-due-per-key NOT EXISTS ordered by
  `(created_at, id)`.** File: `DefaultJdbcOutboxRecordQueries.kt` (`findNonStrictRecordKeys`, `noEarlierDueRecord`). Ids: `keys_nonstrict`.
- **B-4 - Bulk page and per-key reads add `AND completed_at IS NULL`.** Files: `DefaultJdbcOutboxRecordQueries.kt`
  (`findIncompleteByKeysAndStatus`, `findIncompleteByKeyAndStatus`). Ids: `pages`, `find_incomplete_by_key`.
- **B-5 - New `countByStatuses` grouped query** replacing three `countByStatus` calls in the metrics collector (default statuses NEW and FAILED only,
  new property `namastack.outbox.observability.metrics.record-statuses`). Files: `OutboxRecordRepository.kt` (new method), `JdbcRepo`,
  `DefaultJdbcOutboxRecordQueries.kt`, `OutboxMetricsCollector.kt`, `OutboxMetricsProperties.kt`, `spring-configuration-metadata.json`. Ids:
  `count_statuses`, `count_statuses_all` (`count_new`/`count_failed`/`count_completed` remain for main and the deprecated module). Behavioural note:
  `COMPLETED` is no longer counted by default.
- **B-6 - `findOldestCreatedAtByPartitions`: `MIN(created_at)` -> `SELECT created_at ... AND completed_at IS NULL ORDER BY created_at LIMIT 1`;
  `status` parameter removed from the API.** Files: `OutboxRecordRepository.kt`, `JdbcRepo`, `DefaultJdbcOutboxRecordQueries.kt`,
  `OutboxMetricsCollector.kt`. Ids: `oldest_created`.
- **B-7 - `findOldestDueNextRetryAtByPartitions`: strict = `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT 1` with the B-2
  predecessor sub-select; non-strict adds `completed_at IS NULL` (still `MIN`); `status` parameter removed.** Same files. Ids:
  `oldest_due_strict`, `oldest_due_nonstrict`.
- **B-8 - `LIMIT :limit` appended (MariaDB/MySQL class) to the four retention selects; every bounded read also sets `limit` and `withMaxRows`.**
  Files: `DefaultJdbcOutboxRecordQueries.kt`, `MariaDbJdbcOutboxRecordQueries.kt`, `MySqlJdbcOutboxRecordQueries.kt`, `JdbcRepo`. Ids:
  `find_completed_before`, `find_failed_before`, `ids_completed_before`, `ids_failed_before` (main: max-rows only; branch: `LIMIT` and max-rows),
  and the max-rows addition on `keys_*`, `oldest_*`. On MariaDB the driver turns max-rows into `SET STATEMENT SQL_SELECT_LIMIT=n FOR`, so main's
  retention selects already were server-limited.
- **B-9 - Dialect selection machinery.** New `JdbcOutboxRecordQueries` interface, `JdbcOutboxRecordQueriesFactory`, per-dialect classes
  (MariaDB and MySQL classes without overrides), `JdbcDatabaseType.detect` (nullable, non-throwing), repository takes `queries` supplier instead of
  `tableNameResolver`+`databaseType`; `rowLimitClause` removed from `JdbcDatabaseType`; `status` argument removed from
  `findRecordKeysInPartitions` (`OutboxProcessingScheduler.kt`). Files: `JdbcOutboxAutoConfiguration.kt`, `JdbcOutboxPersistenceFactory.kt`,
  `JdbcOutboxRecordRepository.kt`, `JdbcDatabaseType.kt`, `query/*`. No SQL change by itself for MariaDB; revert unit for the
  structure only. Affects: none by id (refactor), except the default-class fallback.

No statement text changed for: `insert`, `upd_*`, `delete_by_id`, `delete_by_ids`, `delete_by_status_failed`, `delete_by_key_status`,
`count_new/failed/completed`, `count_partitions`, `count_partition_single`, `find_pending_all`, `find_failed_all` (verified against both catalog directories).

## 8. Open questions for the orchestrator

1. **Server version / charset of the lanes.** `explicit_defaults_for_timestamp` (first-TIMESTAMP hazard in section 1) and the default charset/collation of
   `id`/`record_key` (not pinned in the DDL) must be recorded; which MariaDB image tag and `character-set-server`?
2. **setMaxRows fidelity.** Against MariaDB >= 10.3 Connector/J 3.5.10 rewrites to `SET STATEMENT SQL_SELECT_LIMIT=n FOR <sql>`. The runner must send exactly this
   (or use the real driver) for `main`'s retention selects, otherwise `main` rows are unlimited. Does the harness use the MariaDB driver for all runs?
   Should the harness also run `main`'s `keys_*`/retention with and without `SQL_SELECT_LIMIT` to separate its effect from `LIMIT`?
3. **`SQL_SELECT_LIMIT` vs `LIMIT` optimizer parity** (including inside `GROUP BY`/`ORDER BY` plans of `keys_nonstrict` on main) is assumed, not verified: confirm via `EXPLAIN FORMAT=JSON`/`ANALYZE`.
4. **Non-strict semantics change (B-3)** may alter result order/ties vs main (main orders keys by min `created_at` only; branch also by `id`); the equivalence check
   should compare as sets for ties, or include `id`.
5. **Default `COMPLETED` count dropped (B-5).** The measured MON group for the branch uses `count_statuses` (NEW+FAILED); should `count_statuses_all` also be a baseline
   to show the COMPLETED-count cost?
6. **MySQL driver path** (MariaDB server via mysql-connector-j or `useMysqlMetadata=true`) selects the MySQL class and `schema/mysql` DDL; the SQL is identical today but the DDL
   differs only cosmetically. Out of scope unless the orchestrator wants it covered.
7. Whether JPA-based deployments on MariaDB (JPA is not measured) use the same `schema/mariadb` DDL and thus the same index change: noted, not verified here (JPA code reads `-jdbc` DDL per CLAUDE.md).

## Addendum (orchestrator, 2026-11-05): branch re-pinned to `2a2663de`

`origin/feature/db-jdbc-optimization` moved from `a06a74c0` to `2a2663de` (fast-forward). The only MariaDB-relevant change is
`oldestCreatedAtByPartitionsStatus` (`oldest_created`): `SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions)
AND status = :status AND completed_at IS NULL`, without `ORDER BY`/`LIMIT` and without `withMaxRows`. B-6 therefore now means "adds
`completed_at IS NULL`" (MIN kept). Everything else in this document is unchanged.
