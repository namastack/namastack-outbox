# Cross-dialect: JPA (Hibernate, JPQL)

Analysis plus SQL captured from `JpaOutboxRecordRepositoryTest` on H2 (`phase3/jpa/generated-sql-h2.txt`); the Postgres plans for the equivalent SQL are those of the JDBC suite (H60), so no new plan runs were needed.

## Query shapes vs JDBC
| method | JPQL / generated SQL | JDBC counterpart |
|---|---|---|
| keys, filter on | `select record_key, min(created_at) ... status=? and next_retry_at<=? and not exists (older with completed_at is null and (created_at< or (= and id<))) group by record_key order by min asc` + `setMaxResults` -> real `limit/fetch first ? rows only` | same anti join as the JDBC baseline; LIMIT is applied after GROUP BY, so no early stop |
| keys, filter off | same without the anti join | same as JDBC |
| oldest due (filter on/off), oldest created | `select min(...)` with `partition_no in (...)`, `status=?`, anti join for the filtered form; no `completed_at` predicate in oldest created | same as JDBC baseline |
| pages | `select new OutboxRecordEntity(...) where record_key in (...) and status=? order by record_key, created_at, id`, no limit | JDBC IN form |
| cleanup selects | `select id ... status=? and completed_at<? order by completed_at fetch first ? rows only` (`setMaxResults`; Hibernate picks LIMIT/FETCH/TOP per dialect) | JDBC real LIMIT |
| counts, deletes, writes | simple equality / IN predicates | same |
`completed_at is null` appears literally in the anti join, so a partial index `WHERE completed_at IS NULL` is usable for the probe; the outer side needs the literal added.

## What carries over from S1
Every S1 rewrite is expressible in JPQL, no native query needed:
- Keys with filter: `select o.recordKey from OutboxRecordEntity o where o.partitionNo in :partitions and o.status = :status and o.nextRetryAt <= :now and o.completedAt is null and not exists (older ... older.completedAt is null and (older.createdAt < o.createdAt or (older.createdAt = o.createdAt and older.id < o.id))) order by o.createdAt asc, o.id asc` with the existing `setMaxResults(batchSize)` (becomes the SQL LIMIT; no GROUP BY needed since there is one head row per key). Same text as the Postgres H08 query.
- Oldest due with filter: the same query selecting `o.nextRetryAt` ordered by `o.nextRetryAt, o.id` with `setMaxResults(1)`, or keep `min()` (H60 form uses the head form).
- Oldest created: add `and o.completedAt is null`.
- Pages: keep the IN form (JPQL has no portable lateral; MariaDB/Oracle/SQL Server also keep IN).
- Keys with filter off: H04 form, needs the extended statistics (database side).
Dialect notes from the other sections still apply when the JPA user runs MySQL/MariaDB (non-partial covering index), SQL Server (filtered index, JPQL emits `fetch first`/`top`), Oracle (`SYS_EXTRACT_UTC` ordering cannot be written in JPQL; a native query or the status-leading index is needed).

## @Table(indexes) and which DDL users actually run
Hibernate 7 `@Index` supports only `columnList` (no `WHERE`, `INCLUDE`). `ddl-auto: create` is dev-only per `docs/reference/database.md`; production JPA users apply the JDBC schema files (Flyway/Liquibase) with `ddl-auto: validate`, so the partial/covering indexes belong in the `schema/<db>/outbox-tables.sql` files and a migration note, not in the annotation.
Note a pre-existing mismatch: the annotation names the key index `idx_outbox_record_key_completed_created`, the JDBC DDL `idx_outbox_record_record_key_completed_created`.
Proposed annotation (dev/ddl-auto only): remove `idx_outbox_record_record_key_created`; add `Index(name = "idx_outbox_record_incomplete_created", columnList = "completedAt, createdAt, id")` (non-partial; it serves the `completed_at is null` outer side and cleanup, but grows with COMPLETED rows). Keep the rest.
