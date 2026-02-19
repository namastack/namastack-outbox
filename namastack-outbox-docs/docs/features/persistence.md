# Persistence Modules

Namastack Outbox provides two persistence modules to choose from based on your needs.

## JPA Module

The JPA module uses Hibernate/JPA for database operations. Best for projects already using Spring Data JPA.

=== "Gradle"

    ```kotlin
    dependencies {
        implementation("io.namastack:namastack-outbox-starter-jpa:{{ outbox_version }}")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-starter-jpa</artifactId>
        <version>{{ outbox_version }}</version>
    </dependency>
    ```

!!! warning "Schema Management"
    The JPA module does **not** support automatic schema creation. You must manage schemas using:
    
    - **Flyway/Liquibase** (recommended for production) - Use the [SQL schema files](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)
    - **Hibernate DDL Auto** (`ddl-auto: create`) for development

---

## JDBC Module

The JDBC module uses Spring's `JdbcClient` for database operations. Best for projects that don't use JPA or want lower overhead.

=== "Gradle"

    ```kotlin
    dependencies {
        implementation("io.namastack:namastack-outbox-starter-jdbc:{{ outbox_version }}")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-starter-jdbc</artifactId>
        <version>{{ outbox_version }}</version>
    </dependency>
    ```

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

!!! note "Database Detection"
    The JDBC module automatically detects your database type and uses the appropriate schema. Supported databases: PostgreSQL, MySQL, MariaDB, H2, SQL Server.

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

!!! warning "Schema Initialization Limitation"
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

**Manual Schema Creation:**

Use the SQL schema files as templates and adjust table names:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

