---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Upgrading to 1.12
description: Replace the outbox_record indexes on each database and adapt dashboards to the outbox.records gauge when upgrading to Namastack Outbox 1.12.
sidebar_position: 12.6
---

# Upgrading to 1.12

Version 1.12 replaces the secondary indexes on `outbox_record` with a set built around incomplete
records. Schema initialization does not remove indexes, so existing installations must migrate them.
The `COMPLETED` value of the `outbox.records` gauge is also no longer collected by default.

:::danger Not tested on live tables
These steps have not been tested as a migration of a live table. Rehearse them on a copy of your data
and check the duration, locking, and disk space on your database and version.
:::

:::note Benchmarks
Only the PostgreSQL indexes and queries are based on benchmarks. The other databases use translated
indexes that have not been measured. Compare your own query latency before and after the upgrade.
:::

## Before You Start

- **Automatic schema initialization** is enabled by default and affects existing tables:
  - PostgreSQL, H2 and SQL Server: startup creates missing indexes without `CONCURRENTLY` or `ONLINE`,
    which can block writes on a large table for the duration of the build. Create the indexes yourself
    before you deploy, as described below.
  - MySQL, MariaDB and Oracle: startup does not add indexes to an existing table, so without the steps
    below the new queries run against the old indexes.
- **Custom names:** if you use a table prefix or a custom schema, adapt the table names.
- **Disk space:** the old and new indexes exist together during the migration.
- **JPA on SQL Server:** the JPA queries select incomplete records by status alone, which the filtered
  indexes of this schema script do not serve. Create the indexes from the
  [MySQL and MariaDB](#mysql-and-mariadb) section instead. See [JPA Module](database.md#jpa-module--no-automatic-schema-creation).

## Migration Order

1. Create the new indexes while the previous version is running.
2. Roll out version 1.12 on all instances. The previous version's queries need the old indexes, so do
   not drop them before the rollout has finished.
3. Drop the old indexes.
4. Update statistics, then check query latency.

On PostgreSQL, the old full status-leading indexes can bring back a slower `BitmapAnd` plan for bulk
record loading between steps 2 and 3. Keep this period short.

## PostgreSQL

Create the indexes. Run each `CONCURRENTLY` statement outside a transaction block:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_status_created_id
    ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_status_retry_partition
    ON outbox_record (status, next_retry_at) INCLUDE (partition_no);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);
```

Check that every index is valid. A failed `CONCURRENTLY` build leaves an invalid index, and
`IF NOT EXISTS` would then skip it. Drop any index listed here and create it again:

```sql
SELECT c.relname
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
WHERE i.indrelid = 'outbox_record'::regclass AND NOT i.indisvalid;
```

After the rollout, drop the old indexes:

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_partition_status_retry;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status_retry;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_completed_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_key_completed_created;
```

Update the statistics of the table:

```sql
ANALYZE outbox_record;
```

If you created the optional `(status, completed_at)` retention index, replace it as described in
[Record Retention](retention.md#indexes).

## MySQL and MariaDB

Create the indexes. On MySQL, you can add `, ALGORITHM=INPLACE, LOCK=NONE` to fail rather than block
writes if the online build is not possible:

```sql
ALTER TABLE outbox_record
    ADD INDEX idx_outbox_record_status_created_id (status, created_at, id, partition_no, next_retry_at, record_key),
    ADD INDEX idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id),
    ADD INDEX idx_outbox_record_status_retry_partition (status, next_retry_at, partition_no),
    ADD INDEX idx_outbox_record_status_partition (status, partition_no);
```

After the rollout, drop the old indexes:

```sql
ALTER TABLE outbox_record
    DROP INDEX idx_outbox_record_record_key_created,
    DROP INDEX idx_outbox_record_partition_status_retry,
    DROP INDEX idx_outbox_record_status_retry,
    DROP INDEX idx_outbox_record_status,
    DROP INDEX idx_outbox_record_record_key_completed_created;

ANALYZE TABLE outbox_record;
```

If you created the schema from a snapshot build, the name `idx_outbox_record_status` can instead be
`idx_outbox_record_status_created`. Hibernate-generated DDL uses
`idx_outbox_record_key_completed_created` for the last index.

## SQL Server

Create the filtered indexes. Add `WITH (ONLINE = ON)` on editions that support it:

```sql
CREATE INDEX idx_outbox_record_status_created_id_incomplete
    ON outbox_record (status, created_at, id)
    INCLUDE (partition_no, next_retry_at, record_key, completed_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_key_created_id_incomplete
    ON outbox_record (record_key, created_at, id)
    INCLUDE (completed_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_status_retry_incomplete
    ON outbox_record (status, next_retry_at)
    INCLUDE (partition_no, completed_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_status_partition_incomplete
    ON outbox_record (status, partition_no)
    INCLUDE (completed_at)
    WHERE completed_at IS NULL;
```

Sessions that write to `outbox_record` need `QUOTED_IDENTIFIER ON`, which is the Microsoft JDBC driver
default.

After the rollout, drop the old indexes:

```sql
DROP INDEX IF EXISTS idx_outbox_record_record_key_created ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_status_retry ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_status ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_status_created ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created ON outbox_record;
DROP INDEX IF EXISTS idx_outbox_record_key_completed_created ON outbox_record;

UPDATE STATISTICS outbox_record;
```

## Oracle

Create the indexes. `ONLINE` requires an edition that supports it; remove it otherwise. `COMPRESS n`
is prefix compression, which needs no Advanced Compression license:

```sql
CREATE INDEX idx_outbox_rec_status_created
    ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key) COMPRESS 1 ONLINE;
CREATE INDEX idx_outbox_rec_k_c_c_id
    ON outbox_record (record_key, completed_at, created_at, id) COMPRESS 1 ONLINE;
CREATE INDEX idx_outbox_rec_s_r_p
    ON outbox_record (status, next_retry_at, partition_no) COMPRESS 1 ONLINE;
CREATE INDEX idx_outbox_rec_s_p
    ON outbox_record (status, partition_no) COMPRESS 2 ONLINE;
```

After the rollout, drop the old indexes. Skip any name that does not exist; `idx_outbox_rec_stat` can
be `idx_outbox_rec_s_c` if you created the schema from a snapshot build:

```sql
DROP INDEX idx_outbox_rec_key_cr;
DROP INDEX idx_outbox_rec_p_s_r;
DROP INDEX idx_outbox_rec_s_r;
DROP INDEX idx_outbox_rec_stat;
DROP INDEX idx_outbox_rec_k_c_c;
```

Then gather statistics:

```sql
BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'OUTBOX_RECORD');
END;
/
```

## H2

H2 is intended for development. Automatic schema initialization creates the new indexes on an existing
file-based database. To remove the old ones:

```sql
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;
DROP INDEX IF EXISTS idx_outbox_record_status_retry;
DROP INDEX IF EXISTS idx_outbox_record_status;
DROP INDEX IF EXISTS idx_outbox_record_status_created;
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created;
```

## Flyway and Liquibase

Add the statements above as a new migration, in the order given in [Migration Order](#migration-order).
`CREATE INDEX CONCURRENTLY` and `DROP INDEX CONCURRENTLY` must run outside a transaction, so configure
those PostgreSQL migrations accordingly. Run the drop statements in a later migration, after the rollout.

## Metrics

The `outbox.records` gauge is published for `new` and `failed` by default. Dashboards and alerts that
read its `completed` series report no data after the upgrade. Choose one of these:

1. Use the completion rate of the `outbox.record.attempt` timer, which needs no database query. See
   [Observability](observability.md).
2. Keep the gauge by adding `completed` to the statuses. This query is slower and its cost grows with
   the retained history:

   ```yaml
   namastack:
     outbox:
       observability:
         metrics:
           record-statuses: new, failed, completed
   ```

The deprecated `namastack-outbox-metrics` module keeps its `outbox.records.count` gauges. Its counts no
longer have a dedicated full index, so they can be slower on large tables.
