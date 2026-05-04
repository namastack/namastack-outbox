---
title: Persistence Modules
description: Choose between JPA, JDBC, and MongoDB persistence modules.
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

# Persistence Modules

Namastack Outbox provides three persistence modules to choose from based on your needs.

## JPA Module

The JPA module uses Hibernate/JPA for database operations. Best for projects already using Spring Data JPA.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation("io.namastack:namastack-outbox-starter-jpa:{{versionLabel}}")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode language="xml" template= {`<dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-starter-jpa</artifactId>
      <version>{{versionLabel}}</version>
</dependency>`} />

</TabItem>
</Tabs>

import Admonition from '@theme/Admonition';

<Admonition type="warning" title="Schema Management">
The JPA module does <strong>not</strong> support automatic schema creation. You must manage schemas using:

- <strong>Flyway/Liquibase</strong> (recommended for production) - Use the <a href="https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema">SQL schema files</a>
- <strong>Hibernate DDL Auto</strong> (<code>ddl-auto: create</code>) for development
</Admonition>

---

## JDBC Module

The JDBC module uses Spring's `JdbcClient` for database operations. Best for projects that don't use JPA or want lower overhead.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation("io.namastack:namastack-outbox-starter-jdbc:{{versionLabel}}")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode language="xml" template= {`<dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-starter-jdbc</artifactId>
      <version>{{versionLabel}}</version>
</dependency>`} />

</TabItem>
</Tabs>

**Benefits:**

- No Hibernate/JPA dependency required
- Built-in automatic schema initialization
- Support for custom table prefixes and schema names
- Lower memory footprint

### Automatic Schema Creation (JDBC Only)

The JDBC module automatically creates outbox tables on startup by default:

```yaml
namastack:
  outbox:
    jdbc:
      schema-initialization:
        enabled: true  # Auto-create tables on startup (default: true)
```

<Admonition type="note" title="Database Detection">
The JDBC module automatically detects your database type and uses the appropriate schema. Supported databases: PostgreSQL, MySQL, MariaDB, H2, SQL Server, Oracle.
</Admonition>

### Custom Table Prefix and Schema Name

The JDBC module supports custom table naming for multi-tenant deployments or naming conventions:

```yaml
namastack:
  outbox:
    jdbc:
      table-prefix: "myapp_"           # Results in: myapp_outbox_record, myapp_outbox_instance, etc.
      schema-name: "outbox_schema"     # Results in: outbox_schema.myapp_outbox_record
```

**Examples:**

| Configuration             | Resulting Table Name          |
|---------------------------|-------------------------------|
| Default                   | `outbox_record`               |
| `table-prefix: "app1_"`   | `app1_outbox_record`          |
| `schema-name: "myschema"` | `myschema.outbox_record`      |
| Both                      | `myschema.app1_outbox_record` |

<Admonition type="warning" title="Schema Initialization Limitation">
When using custom table prefix or schema name, you must disable schema initialization (which is enabled by default). Schema initialization cannot be used with custom naming:

```yaml
namastack:
  outbox:
    jdbc:
      table-prefix: "myapp_"
      schema-name: "custom_schema"
      schema-initialization:
        enabled: false  # Must be false when using custom naming
```
</Admonition>

**Manual Schema Creation:**

Use the SQL schema files as templates and adjust table names:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

---

## MongoDB Module

The MongoDB module uses Spring Data MongoDB for document-based persistence. Best for projects already using MongoDB.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation("io.namastack:namastack-outbox-starter-mongodb:{{versionLabel}}")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode language="xml" template= {`<dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-starter-mongodb</artifactId>
      <version>{{versionLabel}}</version>
</dependency>`} />

</TabItem>
</Tabs>

**Benefits:**

- No SQL or relational database required
- Automatic collection and index creation via Spring Data MongoDB
- Support for custom collection prefixes
- Single-document atomicity for most operations

### Custom Collection Prefix

The MongoDB module supports custom collection naming for multi-tenant deployments or naming conventions:

```yaml
namastack:
  outbox:
    mongodb:
      collection-prefix: "myapp_"         # Results in: myapp_outbox_records, myapp_outbox_instances, etc.
```

**Examples:**

| Configuration                   | Resulting Collection Name |
|---------------------------------|---------------------------|
| Default                         | `outbox_records`          |
| `collection-prefix: "app_"`     | `app_outbox_records`      |
| `collection-prefix: "tenant1_"` | `tenant1_outbox_records`  |

<Admonition type="note" title="Index Creation">
Ensure `spring.data.mongodb.auto-index-creation` is set to `true` (or manage indexes manually) so that the required indexes for outbox collections are created automatically. For production environments, consider using the [manual setup script](mongodb-schema.md) instead.
</Admonition>
