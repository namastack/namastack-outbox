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

Namastack Outbox supports JDBC-compatible relational databases. The JDBC module includes
automatic schema creation for the following databases:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server
- ✅ Oracle

### MongoDB

MongoDB is supported via the dedicated `namastack-outbox-starter-mongodb` module. Automatic index
creation requires `spring.data.mongodb.auto-index-creation=true`. For manual
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

### Applications using Spring Data JPA

Use JDBC outbox persistence with the same DataSource and JpaTransactionManager as your business
entities. Hibernate manages only the business entity schema; initialize outbox tables through JDBC
or Flyway/Liquibase using the [shipped SQL scripts](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema).

---

## Indexes

The schema files create these secondary indexes on `outbox_record`. Their exact definitions differ per
database, and H2 creates only the first two; see the [schema files](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema).

| Index on | Used for |
|----------|----------|
| status, creation time, id | Selecting the record keys to process |
| record key, completion time, creation time, id | Loading the records of a key, and checking for earlier records of a key |
| status, next retry time | The oldest due record metric |
| status, partition | Record count metrics per partition |

Retention queries have no dedicated index and can be slow on large tables. See
[Record Retention](retention.md#indexes).

On PostgreSQL, H2 and SQL Server, automatic schema initialization creates missing indexes at startup,
which can block writes on a large table.

For an existing 1.x database, apply the [2.0 index migration](../migrations/2-0-0-indexes.md).
Startup initialization is not a replacement for versioned schema migrations.

---

## Other Databases

Other databases receive portable SQL without a row-limit clause. The driver applies the row limit, so
the database can read and sort more rows than on a supported database. Automatic schema initialization
does not support them, so disable it and create the schema yourself.

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

**Oracle: `ORA-00955: name is already used by an existing object`**
The initialization script skips tables already present in `USER_TABLES`. This error can indicate
a conflicting object name, an existing index with the same name, or concurrent schema creation.
Inspect the conflicting object and apply the schema through one migration process.

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
| `record_key_completed_created_idx` | `{ recordKey: 1, completedAt: 1, createdAt: 1 }` | Earlier incomplete records for the same key       |
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

Names above match the manual setup script. Spring Data may generate different names for
single-field indexes; compare index fields and options when checking an existing database.

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
