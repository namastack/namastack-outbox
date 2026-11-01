---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Database Support
description: Supported databases for Namastack Outbox, schema management options, automatic initialization, and migration strategies using Flyway or Liquibase for production deployments.
sidebar_position: 12
---

# Database Support

## Supported Databases

Namastack Outbox supports any JPA/JDBC-compatible relational database. The JDBC module includes
automatic schema creation for the following databases:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server
- ✅ Oracle

### MongoDB

MongoDB is supported via the dedicated `namastack-outbox-starter-mongodb` module. Collections
and indexes are created automatically via Spring Data MongoDB on application startup. For manual
index management and index definitions, see [MongoDB Schema](#mongodb-schema). For module
setup and configuration, see [Persistence Modules → MongoDB](persistence.md#mongodb-module).

---

## Schema Management

### JDBC Module — Automatic Initialization

The JDBC module creates the outbox schema automatically on application startup. This is enabled
by default and requires no additional configuration.

To **disable** automatic schema creation (recommended for production deployments that use
migration tools):

```yaml
namastack:
  outbox:
    jdbc:
      schema-initialization:
        enabled: false
```

When disabled, you are responsible for creating the schema using your preferred migration
tooling before the application starts.

### PostgreSQL Processing Indexes

The PostgreSQL schema shipped with the JDBC module uses three indexes on `outbox_record`:

| Index | Purpose |
| --- | --- |
| Primary key on `id` | Record identity and completion updates |
| `idx_outbox_record_key_incomplete_group` | Lookup of all incomplete records for one key |
| `idx_outbox_record_created_id_incomplete` | Ordered scan of the incomplete processing working set |

Both secondary indexes contain only rows where `completed_at IS NULL`. Completed history therefore
does not enlarge the processing indexes. The key index uses `(record_key, (completed_at IS NULL) DESC)`;
the ordered index uses `(created_at, id, partition_no, status, next_retry_at) INCLUDE (record_key)`.
The schema also creates multicolumn MCV statistics on `(status, completed_at)` to help the planner
estimate the incomplete working set. Keep auto-analyze enabled.

The optimized JDBC reader preserves `(created_at, id)` ordering. Strict processing still checks every
earlier incomplete record of the key, including failed records and records awaiting a future retry.
Its PostgreSQL `WITH TIES` boundary retains **all** incomplete rows; it is not a one-record fetch limit.

Fresh installations receive this layout through `schema/postgres/outbox-tables.sql`. Existing
installations require a staged migration. Automatic initialization adds missing indexes but never
removes old ones, so an upgrade alone does not reduce index maintenance to the measured layout.

1. Run `schema/postgres/migrations/01_add_processing_indexes.sql` before upgrading the application.
   It adds the new indexes concurrently, retains the old indexes, creates statistics, and runs `ANALYZE`.
2. Upgrade **every** instance to the optimized JDBC reader. Keep old indexes during the mixed-version rollout.
3. Review application-specific queries, then run `schema/postgres/migrations/02_drop_legacy_processing_indexes.sql`.
   This removes the legacy record indexes and leaves the primary key plus the two partial indexes.

These scripts are packaged in the JDBC JAR and available alongside the schema source. Run them outside
transactions (`CREATE/DROP INDEX CONCURRENTLY` cannot run inside a transaction block). Configure Flyway
or Liquibase accordingly. Adapt identifiers for custom schemas, prefixes, or table names. Check existing
index definitions first: `IF NOT EXISTS` does not repair a differently defined index with the same name.
After a failed concurrent build, inspect and repair invalid indexes before continuing.

Removing status and partition indexes reduces insert/update work, but can make management counts,
completed-history lookup, and cleanup slower on large tables. Retain or add indexes for those operations
when needed; that is a workload tradeoff. The optimized queries also work with the legacy indexes retained.
For JDBC deployments with database-backed state gauges, measure their refresh cost or disable those gauges
as described in [Performance Tuning](performance-tuning.md#jdbc-processing-optimizations).

The schema remains usable by JPA, but these reader and completion optimizations belong to the JDBC
module. JPA and MongoDB persistence are unchanged.

### JPA Module — No Automatic Schema Creation

The JPA module does **not** support automatic schema creation. Schema management is delegated
to Hibernate or your migration tool.

**Option 1: Hibernate DDL Auto (Development Only)**

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create      # or create-drop for test environments
```

This is convenient for local development but should never be used in production — it may drop
and recreate tables on restart.

**Option 2: Flyway or Liquibase (Production)**

Create the outbox tables as part of your migration scripts. Database-specific SQL schema files
are available in the repository:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

Then configure Hibernate to validate the schema on startup rather than modify it:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

If the schema does not match the expected structure, Spring Boot will fail to start — which is
the desired behavior for production deployments.

---

## Migration Strategies

### Flyway

Add the Flyway migration file to `src/main/resources/db/migration/`:

```sql
-- V1__create_outbox_tables.sql
-- Copy the content from the appropriate schema file for your database
```

### Liquibase

Add the outbox table definition to your Liquibase changelog. Use the SQL schema files linked
above as the source for the `sql` changeset type, or translate to Liquibase XML/YAML format.

---

## Troubleshooting

**`Table 'outbox_record' doesn't exist`**
The schema has not been initialized. Either enable automatic initialization (JDBC module) or
apply the migration scripts before starting the application.

**`Schema-validation: missing table`** (JPA module)
Hibernate's `ddl-auto: validate` found a mismatch. Ensure your migration scripts match the
schema files for the current library version. Check for pending migrations.

**`Could not obtain lock on outbox partition`** (PostgreSQL)
This is a normal advisory lock contention message logged at DEBUG level when multiple instances
compete for the same partition. It is not an error — the second instance will retry on the next
poll cycle.

**Oracle: `ORA-00955: name is already used by an existing object`**
Automatic schema initialization tried to create tables that already exist. Disable automatic
initialization and manage the schema manually.

---

## MongoDB Schema

When using the MongoDB persistence module, collections and indexes can be created automatically by Spring Data MongoDB
(`spring.data.mongodb.auto-index-creation=true`). However, for production environments it is recommended to manage
indexes explicitly using a setup script.

### Collections & Indexes

The MongoDB module uses three collections:

| Collection                     | Purpose                                 |
|--------------------------------|-----------------------------------------|
| `outbox_records`               | Stores outbox records (events/messages) |
| `outbox_instances`             | Tracks application instances            |
| `outbox_partition_assignments` | Maps partitions to instances            |

When using a custom collection prefix (e.g. `myapp_`), all collection names are prefixed accordingly
(e.g. `myapp_outbox_records`).

#### outbox_records Indexes

| Index Name                         | Fields                                           | Purpose                                          |
|------------------------------------|--------------------------------------------------|--------------------------------------------------|
| `status_idx`                       | `{ status: 1 }`                                  | Filter records by status                         |
| `record_key_created_idx`           | `{ recordKey: 1, createdAt: 1 }`                 | Ordered lookup by record key                     |
| `partition_status_retry_idx`       | `{ partitionNo: 1, status: 1, nextRetryAt: 1 }`  | Partition-scoped polling query                   |
| `status_retry_idx`                 | `{ status: 1, nextRetryAt: 1 }`                  | Global retry scheduling                          |
| `record_key_completed_created_idx` | `{ recordKey: 1, completedAt: 1, createdAt: 1 }` | Completed record cleanup                         |
| `fifo_pipeline_idx`                | `{ partitionNo: 1, recordKey: 1, createdAt: 1 }` | FIFO aggregation pipeline for ordered processing |

For time-based retention of completed records, an additional `{ status: 1, completedAt: 1 }` index is
recommended but not created automatically. See [Record Retention](retention.md#indexes).

#### outbox_instances Indexes

| Index Name             | Fields                            | Purpose                           |
|------------------------|-----------------------------------|-----------------------------------|
| `status_idx`           | `{ status: 1 }`                   | Filter instances by status        |
| `lastHeartbeat_idx`    | `{ lastHeartbeat: 1 }`            | Stale instance detection          |
| `status_heartbeat_idx` | `{ status: 1, lastHeartbeat: 1 }` | Combined status + heartbeat query |

#### outbox_partition_assignments Indexes

| Index Name       | Fields              | Purpose                           |
|------------------|---------------------|-----------------------------------|
| `instanceId_idx` | `{ instanceId: 1 }` | Lookup partitions by instance     |

---

### Manual Setup Script

A ready-to-use `mongosh` script is provided in the repository:

👉 [mongodb-setup.js on GitHub](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-mongodb/src/main/resources/schema/mongodb-setup.js)

#### Running the Script

**Default collection names:**

```bash
mongosh mongodb://localhost:27017/mydb schema/mongodb-setup.js
```

**With a custom collection prefix:**

```bash
mongosh --eval 'var OUTBOX_PREFIX="myapp_"' mongodb://localhost:27017/mydb schema/mongodb-setup.js
```

This creates `myapp_outbox_records`, `myapp_outbox_instances`, and `myapp_outbox_partition_assignments`.

#### Disabling Auto-Index Creation

When using the manual setup script, disable Spring Data MongoDB's automatic index creation:

```yaml
spring:
  data:
    mongodb:
      auto-index-creation: false
```

:::tip Production Recommendation
For production environments, it is recommended to disable `auto-index-creation` and manage indexes
via the setup script (or your own migration tooling). This gives you full control over when and how
indexes are created, avoiding potential performance impacts during application startup.
:::
