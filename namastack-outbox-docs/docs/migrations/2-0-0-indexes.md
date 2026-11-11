---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Database indexes for 2.0.0
sidebar_label: Database indexes
description: Add the Namastack Outbox 2.0.0 indexes to existing PostgreSQL, MySQL, MariaDB, H2, SQL Server or Oracle tables without changing stored records.
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Database indexes for 2.0.0

Use this page for the database step in [Upgrade to 2.0.0](2-0-0.md#database).
It applies to JDBC and JPA → JDBC upgrades from 1.11.1. MongoDB collections and
indexes do not require these SQL changes.

The table and column layout stays the same. The new indexes support the revised
polling, record-loading and metrics queries. Existing records remain readable with
the old indexes, but that does not establish their performance under your workload.
Install the new indexes as part of the upgrade.

## Add a new migration

Choose the tab for your database and copy its SQL into the **next unused migration
version**, for example `V42__add_outbox_2_indexes.sql`, or a new Liquibase SQL
changeset. Keep the original table-creation migration unchanged. Apply the new
migration before starting 2.0.0 workers.

These scripts assume the default `outbox_record` table and that the new index names
do not exist yet. If schema initialization has already created an index, compare
its definition with the SQL below and omit that creation statement when it matches.
If an earlier attempt partially succeeded, reconcile those indexes before retrying.

For custom schemas, table prefixes or table names, use the existing physical table
name throughout. Qualify names for the target schema; when several outboxes share
a schema, also give each outbox's indexes distinct names.

Each tab links to the complete table and index definitions on `main` for reference.

<Tabs groupId="database">
<TabItem value="postgres" label="PostgreSQL" default>

[Full PostgreSQL schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/postgres/outbox-tables.sql)

```sql
CREATE INDEX idx_outbox_record_status_created_id
    ON outbox_record (status, created_at, id)
    INCLUDE (partition_no, next_retry_at, record_key);

CREATE INDEX idx_outbox_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);

CREATE INDEX idx_outbox_record_status_retry_partition
    ON outbox_record (status, next_retry_at)
    INCLUDE (partition_no);

CREATE INDEX idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);
```

For a maintenance window, run these statements through your migration tool with
writers stopped. For an online build, use `CREATE INDEX CONCURRENTLY` for each
statement and configure that migration to run **outside a transaction**. Inspect
and resolve any invalid indexes left by an interrupted concurrent build before
retrying; an existing name alone does not prove an index is usable.

Check the installed definitions:

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename = 'outbox_record'
ORDER BY indexname;
```

</TabItem>
<TabItem value="mysql" label="MySQL / MariaDB">

[Full MySQL schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/mysql/outbox-tables.sql)
· [Full MariaDB schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql)

```sql
CREATE INDEX idx_outbox_record_status_created_id
    ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key);

CREATE INDEX idx_outbox_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);

CREATE INDEX idx_outbox_record_status_retry_partition
    ON outbox_record (status, next_retry_at, partition_no);

CREATE INDEX idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);
```

These definitions match the shipped InnoDB schemas. Use the indexes' full column
definitions; prefix indexes are not equivalent. If your original table differs in
column widths, character sets or storage options, check its index-size limits
before applying the migration. Select online DDL options supported by your database
version if writers must remain active.

```sql
SHOW INDEX FROM outbox_record;
```

</TabItem>
<TabItem value="h2" label="H2">

[Full H2 schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/h2/outbox-tables.sql)

H2 uses two record indexes:

```sql
CREATE INDEX idx_outbox_record_status_created_id
    ON outbox_record (status, created_at, id);

CREATE INDEX idx_outbox_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);
```

For a persistent H2 database, preserve the existing database file and apply the
migration before restarting the application.

```sql
SELECT INDEX_NAME, COLUMN_NAME, ORDINAL_POSITION
FROM INFORMATION_SCHEMA.INDEX_COLUMNS
WHERE TABLE_NAME = 'OUTBOX_RECORD'
ORDER BY INDEX_NAME, ORDINAL_POSITION;
```

Adjust identifier case if your application uses quoted or lowercase identifiers.

</TabItem>
<TabItem value="sqlserver" label="SQL Server">

[Full SQL Server schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/sqlserver/outbox-tables.sql)

```sql
CREATE INDEX idx_outbox_record_status_created_id
    ON outbox_record (status, created_at, id)
    INCLUDE (partition_no, next_retry_at, record_key);

CREATE INDEX idx_outbox_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);

CREATE INDEX idx_outbox_record_status_retry_partition
    ON outbox_record (status, next_retry_at)
    INCLUDE (partition_no);

CREATE INDEX idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);
```

Use your actual schema, for example `dbo.outbox_record`, when qualifying the table.
Select online index-build options supported by your SQL Server installation if
writers must remain active.

```sql
SELECT i.name, c.name AS column_name, ic.key_ordinal, ic.is_included_column
FROM sys.indexes i
JOIN sys.index_columns ic
  ON ic.object_id = i.object_id AND ic.index_id = i.index_id
JOIN sys.columns c
  ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE i.object_id = OBJECT_ID('outbox_record')
ORDER BY i.name, ic.is_included_column, ic.key_ordinal, ic.index_column_id;
```

</TabItem>
<TabItem value="oracle" label="Oracle">

[Full Oracle schema](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-jdbc/src/main/resources/schema/oracle/outbox-tables.sql)

Oracle uses shorter index names and includes covering columns in the index key:

```sql
CREATE INDEX idx_outbox_rec_status_created
    ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key)
    COMPRESS 1;

CREATE INDEX idx_outbox_rec_k_c_c_id
    ON outbox_record (record_key, completed_at, created_at, id)
    COMPRESS 1;

CREATE INDEX idx_outbox_rec_s_r_p
    ON outbox_record (status, next_retry_at, partition_no)
    COMPRESS 1;

CREATE INDEX idx_outbox_rec_s_p
    ON outbox_record (status, partition_no)
    COMPRESS 2;
```

These are standalone DDL statements for an existing table. Do not copy the
table-creation PL/SQL block from the fresh-install schema: that block creates the
indexes only when it creates the table. Select appropriate online DDL options if
writers must remain active.

```sql
SELECT index_name, column_name, column_position
FROM user_ind_columns
WHERE table_name = 'OUTBOX_RECORD'
ORDER BY index_name, column_position;
```

</TabItem>
</Tabs>

These migrations add record indexes only. Keep the existing primary keys, instance
indexes and partition-assignment indexes.

## Keep schema creation separate from migration

Configure the upgraded application to use your migration tool's schema:

```yaml
namastack:
  outbox:
    jdbc:
      schema-initialization:
        enabled: false
```

On PostgreSQL, H2 and SQL Server, the initializer can add missing indexes to existing
tables at startup. MySQL/MariaDB define indexes inside `CREATE TABLE IF NOT EXISTS`,
and Oracle creates them inside the table-creation block, so running those creation
scripts again does not add the new indexes to an existing table. Explicit migrations
make the upgrade step visible and controllable on every database.

## Remove obsolete indexes after the rollout

Keep the old indexes while 1.x workers are active and during your rollback window.
Once the rollout is verified, remove redundant old indexes in a **separate** migration
to avoid their storage and write overhead. Confirm the actual names and definitions
in your database first, especially if Hibernate or custom migrations created them.

| Default 1.11.1 JDBC index | Oracle equivalent |
|---|---|
| `idx_outbox_record_record_key_created` | `idx_outbox_rec_key_cr` |
| `idx_outbox_record_partition_status_retry` | `idx_outbox_rec_p_s_r` |
| `idx_outbox_record_status_retry` | `idx_outbox_rec_s_r` |
| `idx_outbox_record_status` | `idx_outbox_rec_stat` |
| `idx_outbox_record_record_key_completed_created` | `idx_outbox_rec_k_c_c` |

The former JPA entity used `idx_outbox_record_key_completed_created` for the last
index. Your database can therefore have a different name even with otherwise
default configuration.

Drop only indexes that actually exist and have been superseded. The syntax is:

| Database | Drop syntax (replace `old_index_name`) |
|---|---|
| PostgreSQL / H2 / Oracle | `DROP INDEX old_index_name;` |
| MySQL / MariaDB / SQL Server | `DROP INDEX old_index_name ON outbox_record;` |

Do not drop your own application-specific indexes solely because they are absent
from the new schema. Return to [Deploy and verify](2-0-0.md#deployment) to finish
the application upgrade.
