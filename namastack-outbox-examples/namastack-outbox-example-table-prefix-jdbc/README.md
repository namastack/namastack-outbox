# Namastack Outbox - Custom Table Prefix & Schema Example (JDBC)

This example demonstrates how to use **custom table prefixes** and **custom database schemas** with the Namastack Outbox JDBC module.

## What This Example Shows

- Configuring custom table prefix (`prefix_`) for all outbox tables
- Using a custom database schema (`myschema`) for outbox tables
- Manual schema creation with prefixed table names
- Why automatic schema initialization cannot be used with custom naming

## Key Configuration

```yaml
namastack:
  outbox:
    jdbc:
      schema-name: myschema
      table-prefix: prefix_
```

This configuration results in table names like:
- `myschema.prefix_outbox_record`
- `myschema.prefix_outbox_instance`
- `myschema.prefix_outbox_partition`

## Important Notes

⚠️ **Automatic schema initialization is enabled by default (`namastack.outbox.jdbc.schema-initialization.enabled=true`), but cannot be used together with custom table prefixes or schema names. You must explicitly disable it when using custom naming.**

When using custom naming, you must create the tables manually using:
- Flyway migrations
- Liquibase changesets
- Plain SQL scripts (as shown in this example via `schema.sql`)

## Schema Setup

See `src/main/resources/schema.sql` for the complete schema definition including:
- Custom schema creation (`CREATE SCHEMA myschema`)
- Prefixed outbox tables (`prefix_outbox_record`, `prefix_outbox_instance`, `prefix_outbox_partition`)
- Required indexes for optimal performance
- Application-specific tables (e.g., `prefix_customer`)

## Running the Example

```bash
./gradlew :namastack-outbox-example-table-prefix-jdbc:bootRun
```

Or from the examples directory:

```bash
cd namastack-outbox-examples/namastack-outbox-example-table-prefix-jdbc
./gradlew bootRun
```

## When to Use Custom Table Prefixes

Use custom table prefixes when:
- You need to avoid table name collisions with existing tables
- Multiple applications share the same database schema
- Your organization has naming conventions for database objects
- You want to clearly identify outbox-related tables

## When to Use Custom Schema Names

Use custom schema names when:
- You want to isolate outbox tables in a separate database schema
- Your database security model requires schema-level access control
- You need logical separation of application data and outbox data

