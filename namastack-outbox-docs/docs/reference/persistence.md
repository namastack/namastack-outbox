---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Persistence Modules
description: Choose JDBC for relational databases or MongoDB for document persistence.
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';
import Admonition from '@theme/Admonition';

# Persistence Modules

Namastack Outbox provides JDBC and MongoDB persistence modules. Applications using Spring Data
JPA for business entities use the JDBC outbox module.

## JDBC Module

The JDBC module uses Spring's `JdbcClient` for database operations. Recommended for all relational applications,
including Spring Data JPA projects using a shared DataSource and JpaTransactionManager. Your application
must provide a DataSource and JDBC driver, typically through `spring-boot-starter-jdbc` or
`spring-boot-starter-data-jpa`.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode version="2.0.0" language="kotlin" template= {`dependencies {
      implementation(platform("io.namastack:namastack-outbox-bom:{{versionLabel}}"))
      implementation("io.namastack:namastack-outbox-starter-jdbc")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode version="2.0.0" language="xml" template= {`<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.namastack</groupId>
            <artifactId>namastack-outbox-bom</artifactId>
            <version>{{versionLabel}}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>io.namastack</groupId>
    <artifactId>namastack-outbox-starter-jdbc</artifactId>
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
The record queries are chosen from the same detection on first use, including when schema initialization is disabled. See [Other Databases](database.md#other-databases).
</Admonition>

### Custom Schema Name and Table Prefix

The JDBC module supports custom table naming for multi-tenant deployments or naming conventions:

```yaml
namastack:
  outbox:
    jdbc:
      schema-name: "outbox_schema"     # Results in: outbox_schema.myapp_outbox_record
      table-prefix: "myapp_"           # Results in: myapp_outbox_record, myapp_outbox_instance, etc.
```

**Examples:**

| Configuration             | Resulting Table Name          |
|---------------------------|-------------------------------|
| Default                   | `outbox_record`               |
| `schema-name: "myschema"` | `myschema.outbox_record`      |
| `table-prefix: "app1_"`   | `app1_outbox_record`          |
| Both                      | `myschema.app1_outbox_record` |

### Fully Custom Table Names

When a prefix is not enough — for example when organization-wide standards mandate all-uppercase, case-sensitive
identifiers — you can override the base table names directly:

```yaml
namastack:
  outbox:
    jdbc:
      table-prefix: "ACME_"
      table-names:
        record: "OUTBOX_RECORD"        # Results in: ACME_OUTBOX_RECORD
        instance: "OUTBOX_INSTANCE"    # Results in: ACME_OUTBOX_INSTANCE
        partition: "OUTBOX_PARTITION"  # Results in: ACME_OUTBOX_PARTITION
```

`schema-name` and `table-prefix` are still applied on top of the configured base names.

For complete control over naming (beyond prefix/schema/base-name composition), register your own
`JdbcTableNameResolver` bean. The auto-configuration only provides the default implementation when no such bean exists
(`@ConditionalOnMissingBean`):

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Bean
fun jdbcTableNameResolver(): JdbcTableNameResolver =
    object : JdbcTableNameResolver {
        override val outboxRecord = "ACME_OUTBOX_RECORD"
        override val outboxInstance = "ACME_OUTBOX_INSTANCE"
        override val outboxPartitionAssignment = "ACME_OUTBOX_PARTITION"
    }
```

</TabItem>
<TabItem value="java" label="Java">

```java

@Bean
public JdbcTableNameResolver jdbcTableNameResolver() {
    return new JdbcTableNameResolver() {
        @Override
        public String getOutboxRecord() {
            return "ACME_OUTBOX_RECORD";
        }

        @Override
        public String getOutboxInstance() {
            return "ACME_OUTBOX_INSTANCE";
        }

        @Override
        public String getOutboxPartitionAssignment() {
            return "ACME_OUTBOX_PARTITION";
        }
    };
}
```

</TabItem>
</Tabs>

<Admonition type="warning" title="Schema Initialization Limitation">
When using a custom schema name, table prefix or custom table names, you must disable schema initialization (which is enabled by default). Schema initialization cannot be used with custom naming:

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

The MongoDB module uses Spring Data MongoDB for document-based persistence. Use it alongside
`spring-boot-starter-data-mongodb`, which supplies the MongoDB client and connection configuration.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode version="2.0.0" language="kotlin" template= {`dependencies {
      implementation(platform("io.namastack:namastack-outbox-bom:{{versionLabel}}"))
      implementation("io.namastack:namastack-outbox-starter-mongodb")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode version="2.0.0" language="xml" template= {`<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.namastack</groupId>
            <artifactId>namastack-outbox-bom</artifactId>
            <version>{{versionLabel}}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>io.namastack</groupId>
    <artifactId>namastack-outbox-starter-mongodb</artifactId>
</dependency>`} />

</TabItem>
</Tabs>

**Benefits:**

- No SQL or relational database required
- Collection and index setup via Spring Data MongoDB or the supplied script
- Support for custom collection prefixes
- Transactional persistence of business documents and outbox records

### Transactions

Use a replica set or sharded cluster that supports MongoDB transactions, including for local
development. A standalone MongoDB server is insufficient. Schedule records in a Spring-managed
MongoDB transaction using the same `MongoDatabaseFactory` as your business writes.

The module supplies a manager named `outboxMongoTransactionManager`. If multiple transaction
managers are present, select the MongoDB manager explicitly, for example
`@Transactional("outboxMongoTransactionManager")`.

Create collections and indexes before accepting transactional writes. See
[Spring Data MongoDB transaction setup](https://docs.spring.io/spring-data/mongodb/reference/mongodb/client-session-transactions.html).

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
Ensure `spring.data.mongodb.auto-index-creation` is set to `true` (or manage indexes manually) so that the required indexes for outbox collections are created automatically. For production environments, consider using the [manual setup script](database.md#mongodb-schema) instead.
</Admonition>
