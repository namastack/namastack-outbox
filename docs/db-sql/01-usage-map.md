# `outbox_record` usage map (baseline: `main`, worktree `/home/zamo/git/outbox-wt-main`)

Scope: `OutboxRecordRepository`, JDBC and JPA implementations, all JDBC dialects. MongoDB ignored.
Everything below is read from code. The Hibernate SQL in section 3 is **inferred** from JPQL (not captured from a running
Hibernate). Behaviour of JDBC drivers for `setMaxRows` is stated from general driver knowledge, not verified here.

Paths are relative to the worktree. `core` = `namastack-outbox-core/src/main/kotlin/io/namastack/outbox`.

## 1. Table

### 1.1 Columns (identical logical shape in every dialect)

| column | meaning | NOT NULL |
|---|---|---|
| `id` | record UUID, PK | yes |
| `status` | `NEW` / `COMPLETED` / `FAILED` (string) | yes |
| `record_key` | storage key (business key, optionally group-encoded) | yes |
| `record_type` | payload type name | yes |
| `payload` | serialized payload (LOB) | yes |
| `context` | serialized context map (LOB) | no |
| `created_at` | creation instant | yes |
| `completed_at` | set when marked COMPLETED | no |
| `failure_count` | handler failures so far | yes |
| `failure_reason` | last failure message, truncated to 1000 chars | no |
| `next_retry_at` | earliest time the record may be (re)tried | yes |
| `partition_no` | `murmur3(record_key) & 0x7fffffff % 256` | yes |
| `handler_id` | stable handler identity | yes |

Types per dialect (file `namastack-outbox-jdbc/src/main/resources/schema/<db>/outbox-tables.sql`):

| dialect | id/key/type | status | payload / context | timestamps | ints | failure_reason / handler_id |
|---|---|---|---|---|---|---|
| postgres | VARCHAR(255) | VARCHAR(20) | TEXT / TEXT | TIMESTAMP WITH TIME ZONE | INT / INTEGER | VARCHAR(1000) |
| h2 | VARCHAR(255) | VARCHAR(20) | CLOB / CLOB | TIMESTAMP WITH TIME ZONE | INT / INTEGER | VARCHAR(1000) |
| mysql | VARCHAR(255) | VARCHAR(20) | LONGTEXT utf8mb4_unicode_ci | TIMESTAMP (no fractional spec = second precision) | INT | VARCHAR(1000) |
| mariadb | as mysql (explicit `DEFAULT NULL`) | | | TIMESTAMP | INT | |
| sqlserver | VARCHAR(255) | VARCHAR(20) | NVARCHAR(MAX) | DATETIME2(6) | INT | VARCHAR(1000) |
| oracle | VARCHAR2(255) | VARCHAR2(20) | NCLOB | TIMESTAMP WITH TIME ZONE | NUMBER(10) | VARCHAR2(1000) |

Notes: `handler_id VARCHAR(1000)` and `failure_reason VARCHAR(1000)` are not indexed. MySQL/MariaDB `TIMESTAMP`
columns have second precision as declared (no `(6)`), and `created_at` ordering ties are resolved by `id`.
MariaDB/MySQL `TIMESTAMP` NOT NULL columns: first-TIMESTAMP implicit default behaviour depends on server settings (not verified).

### 1.2 Indexes and constraints (same five in all dialects; PK on `id`)

| name (pg/h2/mysql/mariadb/sqlserver) | oracle name | columns |
|---|---|---|
| PRIMARY KEY | PRIMARY KEY | `(id)` |
| `idx_outbox_record_record_key_created` | `idx_outbox_rec_key_cr` | `(record_key, created_at)` |
| `idx_outbox_record_partition_status_retry` | `idx_outbox_rec_p_s_r` | `(partition_no, status, next_retry_at)` |
| `idx_outbox_record_status_retry` | `idx_outbox_rec_s_r` | `(status, next_retry_at)` |
| `idx_outbox_record_status` | `idx_outbox_rec_stat` | `(status)` |
| `idx_outbox_record_record_key_completed_created` | `idx_outbox_rec_k_c_c` | `(record_key, completed_at, created_at)` |

- No CHECK constraints, no FKs, no partial indexes, no `INCLUDE` columns, no unique constraint besides the PK.
- `(status)` is a strict prefix of `(status, next_retry_at)` (redundant for lookups; only differs in size).
- Postgres/H2 use `CREATE INDEX IF NOT EXISTS` separately; MySQL/MariaDB/SQL Server declare inline; Oracle creates them inside a PL/SQL block only when the table is missing.
- SQL Server: `PRIMARY KEY (id)` is a clustered index on a `VARCHAR(255)` (UUID) key; secondary indexes carry the 255-byte key. InnoDB likewise clusters on `id`.
- Table name can be changed via `JdbcTableNameResolver` (schema, prefix, table name); SQL is built with `$tableName` string templates.

### 1.3 JPA entity (`namastack-outbox-jpa/.../OutboxRecordEntity.kt`)

`@Entity @Table(name="outbox_record")`, data class. Fields map 1:1 to columns above: `status` is `@Enumerated(STRING)`,
`payload`/`context` are `@JdbcTypeCode(SqlTypes.LONG32NVARCHAR)`, timestamps are `Instant`. The `@Table(indexes=...)` lists the same five
indexes but names the last one `idx_outbox_record_key_completed_created` (the SQL DDL says `idx_outbox_record_record_key_completed_created`).
Only matters if Hibernate DDL generation/validation compares index names. There is no `@Version`, no `@DynamicUpdate`.
`MySqlOutboxDialect` / `MariaDbOutboxDialect` only relax Hibernate LOB type-equivalence for schema validation.

## 2. Field semantics and relations

### 2.1 State machine

```
            Outbox.schedule  (INSERT, same tx as business data)
                  |
                  v
   +------------------------------+   handler ok            +-----------+
   | NEW  completed_at=NULL       |------------------------>| COMPLETED |  completed_at=now
   | failure_count=0              |   (or fallback COMPLETE)+-----------+  (or row DELETEd if
   | next_retry_at=created_at     |                                         delete-completed-records=true)
   +------------------------------+
        |  handler fails: failure_count++, failure_reason=msg
        |    retry allowed (failure_count <= maxRetries and retryable)
        |      -> stays NEW, next_retry_at = now + backoff delay      (UPDATE state)
        |    retries exhausted / not retryable
        |      -> fallback handler (if any): COMPLETE disposition -> COMPLETED
        |      -> otherwise (or FAIL disposition / fallback throws) -> FAILED   (UPDATE state)
        v
   +------------------------------+
   | FAILED  completed_at=NULL    |  terminal; next_retry_at keeps value of last scheduled attempt
   +------------------------------+
```

Who sets what:
- `OutboxService.schedule` (core `OutboxService.kt:500`): `OutboxRecord.Builder.build` creates `NEW`, `failure_count=0`, `completed_at=NULL`,
  `next_retry_at = created_at = now`, `partition_no = PartitionHasher(record_key)`; one record (distinct `id`, same business key) per matching handler.
  Requires an active transaction.
- `PrimaryOutboxRecordProcessor` (`processor/PrimaryOutboxRecordProcessor.kt:59-65`): on handler exception `failure_count++`, `failure_reason`; on success `completeRecord`.
- `RetryOutboxRecordProcessor` (`:43`): sets `next_retry_at = now + delay`, persists state, outcome RETRY_SCHEDULED; status stays NEW.
- `FallbackOutboxRecordProcessor` (`:62-69`): may update `failure_reason`; `COMPLETE` -> `completeRecord`.
- `PermanentFailureOutboxRecordProcessor` (`:30-31`): `markFailed()` -> FAILED, persist state. `failure_count` is not incremented here.
- `OutboxRecordProcessor.completeRecord` (`:58-80`): `deleteCompletedRecords=true` -> `deleteById`; else `markCompleted` (sets `completed_at`, status) + `updateProcessingState`.
- Scheduler skips records with `next_retry_at > now` or status != NEW (`OutboxRecord.canBeRetried`).

Invariants:
- NEW: `completed_at IS NULL`, `next_retry_at` may be in the future (waiting retry).
- COMPLETED: `completed_at NOT NULL` (the only status for which `completed_at` is set). With delete-on-complete, COMPLETED rows never exist.
- FAILED: `completed_at IS NULL`; `next_retry_at` is a lower bound for the failure time (not updated on failure).
- Therefore `completed_at IS NULL` <=> status in (NEW, FAILED) ("incomplete"); the predecessor check in the key query uses this and ignores `status`.
- Ordering per key: `(created_at, id)` ascending; `id` (UUID) is only a tie-breaker. Strict ordering when `stop-on-first-failure=true` (default): a key is
  eligible only if no older record of the same key has `completed_at IS NULL` (an older NEW waiting for retry, or an older FAILED, blocks it).
- `record_key` is the storage key (default group: business key; other groups encoded with a reserved prefix). Fan-out: several rows share a business key
  but differ in handler/group, hence in `record_key` when processing groups are on.
- `partition_no` is computed once at insert (256 fixed partitions, MurmurHash3 x86-32, seed 0). Instances own sets of partitions via `outbox_partition`; every
  hot query is filtered by owned `partition_no IN (...)`.
- `failure_reason` truncated to 1000 chars; `handler_id` selects handler/retry policy/fallback, persisted for rolling deployments.

Defaults relevant to load (`core/OutboxProperties.kt`): fixed polling interval 2s (adaptive 1s..8s), `batchSize=10` keys per cycle,
`stopOnFirstFailure=true`, `deleteCompletedRecords=false` (so COMPLETED rows accumulate forever unless an external retention job is used),
max retries 3, exponential backoff.

## 3. Method catalog

Typical-deployment assumptions for frequency: 3 instances, 256 partitions (about 85 each), default polling (fixed 2s), batchSize 10,
`stop-on-first-failure=true`, observability metrics enabled (refresh 15s), 1,000,000 stored rows dominated by COMPLETED (no delete-on-complete), throughput
R records/s.

JDBC notes common to all methods:
- No dialect-specific SQL branches exist in `JdbcOutboxRecordRepository`; one SQL string per method for all six dialects. `toSingleLine()` only collapses whitespace.
- Row limits use `JdbcClient.withMaxRows(n)` -> `Statement.setMaxRows`. SQL text carries no `LIMIT`/`FETCH FIRST`/`TOP`; how the limit
  is applied is driver-dependent (driver may append a limit, or just stop fetching).
- Collections (`IN (:x)`) are expanded by Spring to `?,?,...` placeholders. Partition sets are at most 256 entries. `ids`/`keys` lists are up to
  `limit` / 1000 entries (Oracle IN limit 1000).
- `SELECT *` is used for every record-returning query: payload and context LOBs are always transferred.
- Timestamps bound as `java.sql.Timestamp`. Table name `$T` = `outbox_record` (or prefixed).
- Transactions: writes and deletes run in `transactionTemplate.execute`; reads run without their own transaction (join the ambient one if any, otherwise autocommit).
  No `FOR UPDATE`, no `SKIP LOCKED`, no optimistic version column anywhere.

JPA notes common to all methods:
- JPQL via `EntityManager.createQuery`; `setMaxResults` is translated by the Hibernate dialect (`limit ?` on PG/MySQL/MariaDB/H2-compatible,
  `fetch first ? rows only` on Oracle/H2, `offset 0 rows fetch first ? rows only` on SQL Server).
- `Instant` parameters bound as timestamp with time zone / timestamp(6) depending on dialect; enum bound as string.
- Collection parameters expand to `in (?,?,...)` (Hibernate may pad IN lists if `in_clause_parameter_padding` is set; not configured in code).

### 3.1 Writes

| method | callers | trigger / config | frequency |
|---|---|---|---|
| `create(record)` | `core/OutboxService.kt:500` | per `Outbox.schedule`, once per matching handler, user API, always on | = business event rate x handler fan-out |
| `save(record)` | none in main code (public SPI) | -- | 0 |
| `update(record)` | none in main code | -- | 0 |
| `updateProcessingState(record)` | `processor/OutboxRecordProcessor.kt:73` (complete), `RetryOutboxRecordProcessor.kt:43`, `PermanentFailureOutboxRecordProcessor.kt:31` | per record state change | ~1 per processed record (+1 per retry) |

`create`
- JDBC: `INSERT INTO $T (id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id) VALUES (:id, :status, :recordKey, :recordType, :payload, :context, :partitionNo, :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId)`
- JPA: `entityManager.persist(entity)`; Hibernate `insert into outbox_record (completed_at, context, created_at, failure_count, failure_reason, handler_id, next_retry_at, partition_no, payload, record_key, record_type, status, id) values (?,...)` (flush at tx commit; entity has assigned id so no select).
- Params: 1 row. Tx: caller's business transaction (mandatory). Touches PK + all 5 secondary indexes.

`save` (unused in main)
- JDBC: runs the full-row `UPDATE ... WHERE id=:id`; if 0 rows then INSERT.
- JPA: `find(id)` (SELECT by PK) then `merge` (UPDATE all columns) or `persist`.

`update` (unused in main)
- JDBC full-row UPDATE `UPDATE $T SET status=:status, record_key=:recordKey, record_type=:recordType, payload=:payload, context=:context, partition_no=:partitionNo, created_at=:createdAt, completed_at=:completedAt, failure_count=:failureCount, failure_reason=:failureReason, next_retry_at=:nextRetryAt, handler_id=:handlerId WHERE id=:id`, returns `rows==1`.
- JPA: `find` + `merge`; Hibernate loads the entity (SELECT by PK, payload LOBs included) and issues an UPDATE of all columns if any differ (no `@DynamicUpdate`; exact column set not verified).

`updateProcessingState`
- JDBC: `UPDATE $T SET status=:status, completed_at=:completedAt, failure_count=:failureCount, failure_reason=:failureReason, next_retry_at=:nextRetryAt WHERE id=:id`
- JPA JPQL: `update OutboxRecordEntity o set o.status=:status, o.completedAt=:completedAt, o.failureCount=:failureCount, o.failureReason=:failureReason, o.nextRetryAt=:nextRetryAt where o.id=:id`; Hibernate SQL: `update outbox_record set completed_at=?, failure_count=?, failure_reason=?, next_retry_at=?, status=? where id=?` (bulk HQL update bypasses persistence context).
- Access path: PK lookup. 1 row. Own transaction (the worker thread has no ambient tx). Locks the row (+ index entries). Payload/context not written or serialized.

### 3.2 Hot polling path

| method | caller | trigger | frequency |
|---|---|---|---|
| `findRecordKeysInPartitions` | `core/OutboxProcessingScheduler.kt:165` | every `process()` cycle on every instance with assigned partitions, even when idle | 0.5/s/instance at 2s fixed -> ~1.5/s cluster (adaptive: 0.125-1/s/instance) |
| `findIncompleteRecordPages` | `OutboxProcessingScheduler.kt:176` | once per chunk of up to 1000 keys per cycle, only when keys were found | <= 1 per non-idle cycle |
| `findIncompleteRecordsByRecordKey` | no main callers (superseded by pages) | -- | 0 |
| `deleteById` | `OutboxRecordProcessor.kt:66` | per completed record when `delete-completed-records=true` | = completion rate (config off by default) |

`findRecordKeysInPartitions(partitions, status=NEW, batchSize, ignoreRecordKeysWithPreviousFailure=stopOnFirstFailure)`
- JDBC, strict (default, `true`):
  ```sql
  SELECT o.record_key FROM $T o
  WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
    AND NOT EXISTS (SELECT 1 FROM $T older WHERE older.record_key = o.record_key AND older.completed_at IS NULL
        AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))
  GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC
  ```
  with `setMaxRows(batchSize)`. Non-strict (`false`): the same without the `NOT EXISTS` block.
- JPA: same JPQL, selecting `o.recordKey, min(o.createdAt) as minCreated`; Hibernate SQL: `select o1_0.record_key, min(o1_0.created_at) from outbox_record o1_0 where o1_0.partition_no in (?,?,...) and o1_0.status=? and o1_0.next_retry_at<=? and not exists (select 1 from outbox_record o2_0 where o2_0.record_key=o1_0.record_key and o2_0.completed_at is null and (o2_0.created_at<o1_0.created_at or o2_0.created_at=o1_0.created_at and o2_0.id<o1_0.id)) group by o1_0.record_key order by 2 [limit/fetch first ?]`. Result rows are `Object[]`, only column 0 used; `min` column is returned and discarded.
- Params: up to 256 partitions, `now = Instant.now(clock)`, status. Result <= batchSize (default 10) keys of up to ~255 chars.
- Aggregation (`GROUP BY` + `ORDER BY MIN(created_at)`) must see **all** eligible rows in the owned partitions before the limit applies; the limit cuts the sorted result (on
  engines that apply `setMaxRows` as a client-side cap, the full aggregate is still computed). Cost scales with the number of due NEW rows in owned partitions (backlog), not with batchSize.
- Locking: none (plain read). Concurrency safety comes from partition ownership.
- Index: outer filter `partition_no IN, status =, next_retry_at <=` matches `(partition_no, status, next_retry_at)` (IN on the leading column is a multi-range scan; the range on the third column is applied per partition/status). `created_at` and `record_key` come from the heap/clustered row (not in the index); aggregation needs a hash/sort group. Inner `NOT EXISTS`: equality on `record_key`, `completed_at IS NULL`, range on `created_at` -> `(record_key, completed_at, created_at)` serves it fully except the `id` tie-break (heap read when `created_at` ties). `(record_key, created_at)` is an alternative path but needs a `completed_at` filter on heap rows. Per outer row cost: one index probe on `idx_..._key_completed_created` (anti-semijoin). In steady state (no backlog) both sides are tiny.
- Also note that an older record in status NEW still waiting for retry, or FAILED, produces a non-empty subquery and hides the whole key (strict ordering).

`findIncompleteRecordPages(keys)` (keys <= 1000, constant `MAX_RECORD_KEYS_PER_FETCH`)
- JDBC: `SELECT * FROM $T WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id` with status `NEW`; rows are grouped in memory by key, wrapped in `LazyOutboxRecordPage` (payload deserialized lazily, but LOB columns are read from the ResultSet eagerly by the row mapper).
- JPA: constructor-expression JPQL `select new OutboxRecordEntity(o.id, o.status, o.recordKey, ..., o.handlerId) from OutboxRecordEntity o where o.recordKey in :recordKeys and o.status = :status order by o.recordKey, o.createdAt, o.id` -> `select id,status,record_key,record_type,payload,context,partition_no,created_at,completed_at,failure_count,failure_reason,next_retry_at,handler_id from outbox_record where record_key in (?...) and status=? order by record_key, created_at, id`. Not attached to the persistence context.
- Result size: all NEW records of the selected keys (including future retries and records of the same key behind the head) with their full payloads. Typically ~batchSize rows (more if keys have backlogs).
- Index: `(record_key, created_at)` (IN on leading column, ordered output, `status` filtered on heap) or `(record_key, completed_at, created_at)` (completed_at NULL is not a filter here; ordering not usable) -> `idx_..._record_key_created` is the natural path; `status` and `id` filtered/ordered after heap fetch.

`findIncompleteRecordsByRecordKey(key)` (not called in main): `SELECT * FROM $T WHERE record_key=:recordKey AND status=:status ORDER BY created_at, id` (status=NEW); JPA JPQL equivalent. Index `(record_key, created_at)`.

`deleteById(id)`
- JDBC: `DELETE FROM $T WHERE id=:id`. JPA: `find(id)` (SELECT incl. payload) then `remove` -> `select ... from outbox_record where id=?` + `delete from outbox_record where id=?` (2 statements; the SELECT loads LOB payload).
- PK access; deletes from PK and 5 secondary indexes.

### 3.3 Monitoring and metrics

Callers and conditions:
- `observability` module: `observability/metrics/OutboxMetricsCollector.kt` — a single background task, `scheduleWithFixedDelay(refresh, 15s)` (default `refreshInterval`), reads are cached and exposed as gauges. Per refresh: 3x `countByStatus` (lines 148), then `countRecordsByPartitions` (:174), `findOldestCreatedAtByPartitions` (:182), `findOldestDueNextRetryAtByPartitions` (:192, with `stopOnFirstFailure`). Total 6 queries per 15s per instance = ~0.4 queries/s/instance, independent of scrape rate. Bean created through `OutboxInstanceMetricsAutoConfiguration` when the observability module is on the classpath.
- deprecated `metrics` module (`@Deprecated`, `OutboxMetricsAutoConfiguration`): gauges evaluated **per scrape/read**, uncached.
  - `OutboxRecordMetricsMeterBinder.kt:36`: 3 gauges (`outbox.records.count{status=new|failed|completed}`) each call `countByStatus` -> 3 queries per scrape.
  - `OutboxPartitionMetricsMeterBinder.kt:33,39,45,54` + `OutboxPartitionMetricsProvider.kt:38`: four gauges each call `getProcessingStats()`, which runs one `countRecordsByPartition(partition, NEW)` **per assigned partition** -> 4 x (~85) = ~340 point count queries per scrape per instance (2 of 4 gauges actually need only the partition list but still trigger the counts: `assigned.count` computes counts too).
  - Frequency = scrape interval (e.g. 15-60s).

| method | SQL (JDBC) | JPA Hibernate SQL | params | index / path |
|---|---|---|---|---|
| `countByStatus(status)` | `SELECT COUNT(*) FROM $T WHERE status=:status` | `select count(*) from outbox_record where status=?` | status | `(status)` or `(status,next_retry_at)` index scan; **cost = number of rows with that status**; COMPLETED is the largest set (grows without bound when delete-on-complete off); PG needs index-only scan + visibility map, InnoDB scans the secondary index, SQL Server/Oracle scan the narrowest index |
| `countRecordsByPartition(p, status)` | `SELECT COUNT(*) FROM $T WHERE partition_no=:partitionNo AND status=:status` | `select count(*) from outbox_record where partition_no=? and status=?` | 1 partition | `(partition_no,status,next_retry_at)` equality prefix; index-only count |
| `countRecordsByPartitions(ps, status)` | `SELECT COUNT(*) FROM $T WHERE partition_no IN (:partitions) AND status=:status` | `... where partition_no in (?...) and status=?` | <=256 | same index, multi-range; returns 0 without query when empty; **counts future-dated retries too** |
| `findOldestCreatedAtByPartitions` | `SELECT MIN(created_at) FROM $T WHERE partition_no IN (:partitions) AND status=:status` | `select min(created_at) from outbox_record where partition_no in (?..) and status=?` | <=256 | `(partition_no,status,next_retry_at)` finds the rows but `created_at` is not in the index -> must read heap/clustered row for every matching NEW row (cost ~ NEW backlog in owned partitions); no index can give MIN directly |
| `findOldestDueNextRetryAtByPartitions` (strict) | `SELECT MIN(o.next_retry_at) FROM $T o WHERE o.partition_no IN (:partitions) AND o.status=:status AND o.next_retry_at<=:now AND NOT EXISTS (older ... same predecessor check as keys query)` | same shape | <=256 + now | outer: `(partition_no,status,next_retry_at)` (next_retry_at is the 3rd column so MIN is index-only per partition when no NOT EXISTS; with the anti-join each due row probes `(record_key,completed_at,created_at)`); non-strict variant drops NOT EXISTS |

The result for the two MIN queries is read by `rs.getInstantOrNull(1)` (null if no rows). JDBC reads via `query { rs, _ -> ... }.list().firstOrNull()`; JPA uses `singleResult` with `Instant` result type.

Index-only caveat for counts: `count(*)` with `status =` alone on `(status)` is served index-only on most engines; on Postgres only if the visibility map is current (frequent updates of `status` keep it dirty).

### 3.4 Retention / cleanup / admin

| method | callers | trigger / config | frequency |
|---|---|---|---|
| `deleteCompletedRecords(before, limit)` | none in main (public SPI since 1.11.0; for external/user retention jobs) | caller-driven, repeated until `< limit` | n/a |
| `deleteFailedRecords(before, limit)` | none in main | same | n/a |
| `findCompletedRecords(before, limit)`, `findFailedRecords(before, limit)` | none in main | listing for external retention/admin | n/a |
| `findPendingRecords/findCompletedRecords()/findFailedRecords()` (no args) | none in main (tests/users) | full table listing by status | n/a |
| `deleteByIds(ids)` | called by `deleteCompletedRecords`/`deleteFailedRecords` | -- | per retention batch |
| `deleteByStatus(status)` | `actuator/OutboxActuatorEndpoint.kt:45` (`@DeleteOperation`) | manual admin HTTP DELETE | rare, manual |
| `deleteByRecordKeyAndStatus(key, status)` | `actuator/OutboxActuatorEndpoint.kt:33` | manual admin | rare, manual |

There is no scheduled retention job in the repo; COMPLETED rows are only removed by `deleteCompletedRecords=true`, by the actuator, or by application code
calling the SPI.

`findCompletedRecords(before, limit)`
- JDBC: `SELECT * FROM $T WHERE status=:status AND completed_at < :cutoff ORDER BY completed_at` (+`setMaxRows(limit)`). JPA: `select o from OutboxRecordEntity o where o.status=:status and o.completedAt<:cutoff order by o.completedAt asc` + `setMaxResults`.
- Index: no index on `(status, completed_at)`. Candidates: `(status)`/`(status,next_retry_at)` scan (all COMPLETED rows) then filter + sort on `completed_at`; or `(record_key, completed_at, created_at)` full index scan (leading column unconstrained, not seekable). Likely path: scan of all COMPLETED rows + top-N sort. Cost grows with COMPLETED volume; returns `SELECT *` (LOBs) up to `limit` rows.

`findFailedRecords(before, limit)`
- `... WHERE status=:status AND next_retry_at < :cutoff ORDER BY next_retry_at` (status=FAILED) + limit. Index `(status, next_retry_at)`: seek on status, range + order on next_retry_at -> fully served, early termination at `limit`.

`findPendingRecords()`, `findCompletedRecords()`, `findFailedRecords()`
- `SELECT * FROM $T WHERE status=:status ORDER BY created_at, id` (JPQL equal with `createdAt asc, id asc`). Unbounded result (all rows of the status, with LOBs). Index `(status)` / `(status,next_retry_at)` only for the filter; sort on `created_at,id` required. No main callers.

`deleteCompletedRecords(before, limit)` (own tx; selects then deletes)
1. `SELECT id FROM $T WHERE status=:status AND completed_at < :cutoff ORDER BY completed_at` (+max rows/limit) (JPA `select o.id ... order by o.completedAt asc`, `setMaxResults(limit)`)
2. `deleteByIds(ids)`: `DELETE FROM $T WHERE id IN (:ids)` (JPA `delete from OutboxRecordEntity o where o.id in :ids` -> `delete from outbox_record where id in (?,...)`).
- Step 1 access path as for `findCompletedRecords(before, limit)` (no `(status, completed_at)` index). Step 2 PK lookups; each deleted row maintains 5 secondary indexes. `limit` bounded by IN limits (Oracle 1000). Locks: row locks of deleted rows; the select does not lock, so a row can change between steps (documented).

`deleteFailedRecords(before, limit)`: same two steps with `status=FAILED AND next_retry_at < :cutoff ORDER BY next_retry_at` (index `(status, next_retry_at)` serves step 1).

`deleteByStatus(status)`: `DELETE FROM $T WHERE status=:status` (JPA bulk HQL delete). One unbounded statement in one transaction; index `(status)`. Deleting COMPLETED at scale produces one huge transaction (undo/WAL/log growth, long row locks).

`deleteByRecordKeyAndStatus(key, status)`: `DELETE FROM $T WHERE record_key=:recordKey AND status=:status`; JPA bulk HQL. Index `(record_key, created_at)` (status filtered on heap).

`deleteByIds(ids)`: see above; returns 0 without query for an empty collection; the int is the DB update count.

## 4. Grouping by purpose

1. **Hot processing path** (`findRecordKeysInPartitions`, `findIncompleteRecordPages`, `updateProcessingState`, `deleteById`): runs on every poll of every instance. The key query is the dominant read: runs even with an empty outbox (~1.5 q/s at 3 instances with default polling), aggregates over all due NEW rows in owned partitions, has a correlated anti-join in strict mode. Latency determines delivery latency and DB CPU at idle; at backlog its cost grows with the number of due NEW rows (not with batchSize). Pages query returns payload LOBs. `updateProcessingState` is the per-record write at throughput R.
2. **Writes** (`create`, plus unused `save`/`update`): `create` rides the business transaction, so insert cost (6 index entries, LOBs) adds directly to business latency.
3. **Monitoring** (`countByStatus`, `countRecordsByPartition(s)`, `findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions`): observability collector ~6 queries per 15s per instance; deprecated metrics module adds uncached per-scrape queries (3 counts + ~340 per-partition counts). `countByStatus(COMPLETED)` is O(stored completed rows). MIN(created_at) and the due-MIN with anti-join are O(NEW backlog).
4. **Retention/cleanup** (`deleteCompletedRecords`, `deleteFailedRecords`, `deleteByIds`, `find*(before,limit)`): no in-repo scheduler; two-step select ids + `IN` delete. The step-1 query for COMPLETED has no supporting `(status, completed_at)` index. Unbounded growth of COMPLETED with default config inflates every index and count.
5. **Admin/listing** (`deleteByStatus`, `deleteByRecordKeyAndStatus`, no-arg `find*Records`, `findIncompleteRecordsByRecordKey`): manual or unused in main; unbounded `SELECT *`/single-statement deletes.

## 5. Write amplification

Rows are updated in place; each row is touched once at insert and 1+N times later (N retries).

| statement | columns written | secondary indexes whose key columns change |
|---|---|---|
| INSERT (`create`) | all 13 | all 5 + PK (new entries) |
| `updateProcessingState` complete | `status`, `completed_at`, (`failure_count`, `failure_reason`, `next_retry_at` rewritten with same/changed values) | `(partition_no,status,next_retry_at)`, `(status,next_retry_at)`, `(status)`, `(record_key,completed_at,created_at)`; **not** `(record_key,created_at)` |
| `updateProcessingState` retry scheduled | `failure_count`, `failure_reason`, `next_retry_at` (status unchanged, completed_at null) | `(partition_no,status,next_retry_at)`, `(status,next_retry_at)` (next_retry_at changes); `(status)` and the key indexes untouched |
| `updateProcessingState` failed | `status` (+ `failure_reason`/`failure_count` possibly changed) | `(partition_no,status,next_retry_at)`, `(status,next_retry_at)`, `(status)` |
| full `update`/`save` (unused) | all 12 non-id columns incl. payload/context LOBs | engine updates index entries only for columns whose value changed |

Notes:
- Index maintenance happens only when the indexed column value changes (InnoDB, SQL Server, Oracle); Postgres writes new index tuples for every index on a non-HOT update. Since `status`/`next_retry_at` are indexed and change on every state update, updates are never HOT in Postgres (4 new index tuples for completion, plus dead tuples/vacuum load). Rows contain `payload`/`context` in TOAST (PG) / LOB segments, which are untouched by `updateProcessingState` (the interface contract forbids writing them).
- Each processed record = 1 insert + 1 update (success path) or 1 + (retries+1) updates; with delete-on-complete: 1 insert + 1 delete.
- `(status)` is touched on every status change but is redundant with `(status,next_retry_at)`.

## 6. Open questions

1. Is a scheduled retention job expected outside this repo (no caller of `deleteCompletedRecords`/`deleteFailedRecords`/`find*(before,limit)` exists in main)? Which retention volumes should the benchmark assume?
2. Should the SQL optimization target `COMPLETED` accumulation (default `delete-completed-records=false`), e.g. size of `countByStatus(COMPLETED)`, retention index `(status, completed_at)`?
3. Is the deprecated `metrics` module (per-scrape per-partition counts) in scope, or only the observability collector?
4. How are `setMaxRows`/`setMaxResults` realised per driver (verify by capturing SQL in the dialect integration tests); in particular whether the aggregate in `findRecordKeysInPartitions` is limited server-side.
5. Typical backlog depth and key cardinality (keys per partition, records per key) to choose between `GROUP BY` plans and the cost of the correlated `NOT EXISTS`.
6. MySQL/MariaDB `TIMESTAMP` second precision vs `Instant` microsecond tie-breaks with `id`: acceptable? (ordering `(created_at, id)` depends on it.)
7. JPA entity names the fifth index `idx_outbox_record_key_completed_created` while DDL names it `idx_outbox_record_record_key_completed_created`; is the mismatch relevant for any schema validation?
8. Isolation: no file under `docs/db/` was opened or read.
