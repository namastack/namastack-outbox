# Namastack Outbox – JPA Table Prefix Example (H2)

This example demonstrates how to run **namastack-outbox with JPA** while using a **table prefix** by configuring Hibernate’s `PhysicalNamingStrategy`.

It uses **H2 in-memory** and lets **Hibernate create the schema** on startup (`ddl-auto=create-drop`).

## What This Example Shows

- Auto-configuration - just add the dependency and it works
- Using **Hibernate DDL** (`spring.jpa.hibernate.ddl-auto=create-drop`) to automatically create tables
- Prefixing table names via `spring.jpa.hibernate.naming.physical-strategy`
- Disabling the outbox SQL schema initializer (because Hibernate creates the tables in this example)

## How Table Prefixing Works Here

The key piece is a custom Hibernate naming strategy:

- `spring.jpa.hibernate.naming.physical-strategy=io.namastack.demo.PrefixedPhysicalNamingStrategy`
- `PrefixedPhysicalNamingStrategy` prepends `custom_prefix_` to the physical table name

Because Hibernate uses the physical naming strategy for **both DDL generation and runtime SQL**, the following happens automatically:

1. Hibernate creates tables like `custom_prefix_outbox_record`, `custom_prefix_outbox_instance`, …
2. JPA queries issued by namastack-outbox target those prefixed table names

> Note: this naming strategy currently prefixes **all** tables in the persistence unit (including the demo `customer` table). If you only want to prefix outbox tables, adjust the strategy to prefix only table names that start with `outbox_`.

## Custom Database Schema (H2)

This example also uses a dedicated schema named `myschema`:

- The schema is created on H2 startup via the JDBC URL:
  - `INIT=CREATE SCHEMA IF NOT EXISTS myschema`
- Hibernate is instructed to create tables in that schema via:
  - `hibernate.default_schema=myschema`

This is useful when you want to keep outbox (and application) tables in a non-default schema, e.g. in shared databases or to better mirror production setups.

## Schema Management Notes

This example intentionally sets:

- `namastack.outbox.jdbc.schema-initialization.enabled=false`

because schema creation is handled by Hibernate (`ddl-auto=create-drop`). Note that schema initialization is enabled by default in the JDBC module, but must be disabled when using custom table prefixes or schema names.

For production usage, we recommend managing the outbox schema explicitly via **Flyway** or **Liquibase** and treating `ddl-auto=create/create-drop` as a dev/test convenience.

## Running the Example

```bash
./gradlew :namastack-outbox-example-table-prefix-jpa:bootRun
```

The application will:
1. Register two customers
2. Schedule outbox records
3. Process records via outbox handlers
4. Remove both customers and process the removal events

## Configuration

See `src/main/resources/application.yml` for:
- H2 datasource (including a custom schema)
- Hibernate `ddl-auto` and naming strategy configuration
- Outbox schema initialization configuration
