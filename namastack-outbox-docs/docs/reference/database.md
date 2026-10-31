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

### outbox_record Indexes

The schema files create these indexes on `outbox_record` in addition to the primary key on `id`.
They support the poller's queries, which read only incomplete records (`NEW` and `FAILED`, whose
`completed_at` is `NULL`) in `(created_at, id)` order and stop after the batch size.

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_outbox_record_partition_status_retry` | `(partition_no, status, next_retry_at)` | Pending record counts per partition |
| `idx_outbox_record_status_retry` | `(status, next_retry_at)` | Overdue-age metric, `FAILED` retention |
| `idx_outbox_record_status` | `(status)` | Record counts by status |
| `idx_outbox_record_record_key_completed_created_id` | `(record_key, completed_at, created_at, id)` | Ordered records of one key; predecessor check |
| incomplete-record index | per database, see below | Record keys ready for processing, oldest creation time |

The incomplete-record index differs per database:

| Database | Definition |
|----------|------------|
| PostgreSQL | `idx_outbox_record_created_id_incomplete (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL`, plus the extended statistics `outbox_record_status_completed_stx (mcv) ON status, completed_at` |
| SQL Server | `idx_outbox_record_created_id_incomplete (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL` |
| MySQL, MariaDB, H2 | `idx_outbox_record_completed_created_id (completed_at, created_at, id, partition_no, status, next_retry_at, record_key)` |
| Oracle | `idx_outbox_rec_status_created (status, created_at, id, partition_no, next_retry_at, record_key)` |

Oracle index names are shortened: `idx_outbox_rec_p_s_r`, `idx_outbox_rec_s_r`, `idx_outbox_rec_stat`
and `idx_outbox_rec_k_c_c_id`.

The JDBC module chooses the SQL for each database from the product name reported by the JDBC driver.
Other databases receive portable SQL without a SQL row limit; the row limit is then applied by the
driver. Correct results do not depend on these indexes, but without them the poller's queries read
all incomplete records on every poll.

The poller's queries rely on `completed_at` being `NULL` exactly for `NEW` and `FAILED` records.
The library maintains this; applications that write to `outbox_record` directly must keep it.

SQL Server requires `QUOTED_IDENTIFIER ON` in sessions that write to a table with a filtered index.
The Microsoft JDBC driver sets it by default.

Automatic schema initialization creates each index as follows: on PostgreSQL and H2, every missing
index is created, also when the table is already present. On SQL Server, the filtered index is created
when it is missing and the other indexes together with the table. On MySQL, MariaDB and Oracle, all
indexes are created together with the table.

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

| Index Name                   | Fields                                                                            | Partial filter        | Purpose                                              |
|------------------------------|-----------------------------------------------------------------------------------|-----------------------|------------------------------------------------------|
| `status_idx`                 | `{ status: 1 }`                                                                   |                       | Filter records by status                             |
| `record_key_created_idx`     | `{ recordKey: 1, createdAt: 1 }`                                                  |                       | Ordered lookup by record key                         |
| `partition_status_retry_idx` | `{ partitionNo: 1, status: 1, nextRetryAt: 1 }`                                   |                       | Partition-scoped counts and oldest due record        |
| `status_retry_idx`           | `{ status: 1, nextRetryAt: 1 }`                                                   |                       | Global retry scheduling                              |
| `incomplete_created_idx`     | `{ createdAt: 1, _id: 1, partitionNo: 1, status: 1, nextRetryAt: 1, recordKey: 1 }` | `{ completedAt: null }` | Record keys ready for processing, oldest creation time |
| `incomplete_key_created_idx` | `{ recordKey: 1, createdAt: 1, _id: 1 }`                                          | `{ completedAt: null }` | Oldest incomplete record of a key, bulk record load  |

Queries use the partial indexes only when they contain `completedAt: null`; the outbox queries do.
Spring Data MongoDB creates the declared indexes and leaves other indexes on the collection in place.

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
