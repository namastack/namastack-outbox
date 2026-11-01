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

The JDBC module provides query dialects and automatic schema creation for the following databases:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server
- ✅ Oracle

The JDBC auto-configuration detects the database type from application datasource metadata during
startup, including when automatic schema creation is disabled. The database must be reachable at
that point. The databases listed above use tested, optimized dialects. Unrecognized products use
best-effort portable queries with JDBC row limits when schema initialization is disabled. A JDBC
driver alone does not guarantee compatible SQL or type mappings; verify an unlisted database with
your schema and workload. Automatic schema creation still requires one of the listed databases.
Missing or inaccessible metadata fails startup.

The programmatic `JdbcOutboxPersistenceFactory` opens no connection during construction. It detects
the dialect on the first record operation that needs SQL, or uses an explicitly supplied
`JdbcDatabaseType`.

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

### PostgreSQL Indexes and Upgrades

The JDBC PostgreSQL schema uses the primary key plus five record indexes:

| Index columns | Scope | Purpose |
| --- | --- | --- |
| `(record_key, created_at, id)`, including partition, status and retry time | Incomplete records | Strict head selection and ordered bulk loading |
| `(status, next_retry_at)`, including partition and creation time | Incomplete records | Retry selection and pending/failed metadata |
| `(status, partition_no)` | All records | Global and partition-scoped status counts |
| `(record_key, status)` | All records | Key/status lookup and administrative deletion |
| `(completed_at)` | Completed records | Ordered, bounded retention reads |

The partial predicates are `completed_at IS NULL` for incomplete records and
`completed_at IS NOT NULL` for completed records. Retry and pending-status queries include the
incomplete predicate explicitly, so PostgreSQL can use those partial indexes with generic prepared
plans too. Strict processing still checks the first incomplete record of each key before applying
readiness filters; a failed or future-retry predecessor continues to block later records.

Automatic initialization adds missing indexes using `CREATE INDEX IF NOT EXISTS`. It preserves
existing indexes and records; it does not rename, rebuild, or drop indexes. Large index builds can
take time and block writes, so use your migration tool and concurrent index creation when appropriate.

An upgraded installation may therefore have more indexes than a fresh installation, with additional
write and storage cost. After creating the new indexes and checking application-specific queries,
review the older `idx_outbox_record_record_key_created`, `idx_outbox_record_partition_status_retry`,
`idx_outbox_record_status_retry`, `idx_outbox_record_status`, and
`idx_outbox_record_record_key_completed_created` indexes for removal through your migrations.
These names assume the default table namespace. Do not expect fresh-schema benchmark throughput
while retaining all older indexes. Other database schemas are unchanged.

An index provides an access path, not constant-time counts or guaranteed index scans. PostgreSQL
may correctly scan the table when an operation reads or deletes most records. Test custom reporting
queries and retention jobs with your own data distribution.

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
