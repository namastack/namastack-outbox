# Namastack Outbox - Flyway JPA Example

This example demonstrates how to use the Namastack Outbox pattern with **Flyway migrations** for manual schema management with the JPA module.

## What This Example Shows

- Manual schema creation using Flyway migrations instead of Hibernate's `ddl-auto`
- Outbox tables created via versioned migration scripts
- JPA module with `hibernate.ddl-auto: validate` (schema validation only)
- Production-ready schema management approach
- Proper index definitions for optimal query performance

## Key Components

- **Flyway Migrations**: SQL scripts in `src/main/resources/db/migration/`
  - `V1__outbox_tables.sql`: Creates all outbox tables with indexes
  - `V2__create_customer_table.sql`: Creates application-specific tables
- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **CustomerRegisteredOutboxHandler**: Typed handler that processes `CustomerRegisteredEvent` payloads
- **GenericOutboxHandler**: Generic handler that processes any payload type

## Schema Management

This example uses Flyway for schema management, which is the **recommended approach for production environments**:

1. **Flyway** creates and migrates database schema from SQL scripts
2. **Hibernate** validates the schema matches entity definitions (`ddl-auto: validate`)
3. **No automatic schema changes** - all changes are explicit and versioned

## Running the Example

```bash
./gradlew :namastack-outbox-example-flyway-jpa:bootRun
```

The application will:
1. Run Flyway migrations to create outbox and customer tables
2. Register two customers (John Wayne and Macy Grey)
3. Schedule outbox records for customer registration events
4. Process the records asynchronously via outbox handlers
5. Remove both customers after a short delay
6. Schedule and process removal events

## Configuration

See `application.yml` for:
- H2 database configuration (for demo purposes)
- Hibernate validation mode (`ddl-auto: validate`)
- Outbox configuration

## Why Use Flyway with JPA?

- **Version Control**: Schema changes are tracked and versioned
- **Reproducibility**: Same migrations run in all environments
- **Safety**: No accidental schema modifications
- **Audit Trail**: Clear history of all schema changes
- **Team Collaboration**: Schema changes are code-reviewed like any other code

