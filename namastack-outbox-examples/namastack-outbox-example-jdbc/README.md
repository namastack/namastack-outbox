# Namastack Outbox - JDBC Example

This example demonstrates the basic usage of the Namastack Outbox pattern with the **JDBC starter** and **H2 in-memory database**.

## What This Example Shows

- Basic outbox pattern setup with JDBC (no JPA/Hibernate required)
- Using `namastack-outbox-starter-jdbc` for minimal setup
- Scheduling outbox records using `Outbox.schedule()`
- Processing outbox records with typed and generic handlers
- Auto-configuration - just add the dependency and it works
- Plain JDBC repository implementation without JPA entities
- In-memory database configuration for quick testing

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **CustomerRepository**: Plain JDBC repository implementation using `JdbcTemplate`
- **CustomerRegisteredOutboxHandler**: Typed handler that processes `CustomerRegisteredEvent` payloads
- **GenericOutboxHandler**: Generic handler that processes any payload type
- **H2 Database**: In-memory database for quick setup without external dependencies

## Key Differences from JPA Example

- Uses `namastack-outbox-starter-jdbc` instead of `namastack-outbox-starter-jpa`
- No JPA entities - plain Kotlin data classes
- Custom JDBC repository using `JdbcTemplate`
- Uses `@Transactional` from Spring's transaction support
- Schema initialization via `schema.sql` instead of JPA DDL

## Running the Example

```bash
./gradlew :namastack-outbox-example-jdbc:bootRun
```

Or from the root project:

```bash
cd namastack-outbox-examples/namastack-outbox-example-jdbc
./gradlew bootRun
```

The application will:
1. Register two customers (John Wayne and Macy Grey)
2. Schedule outbox records for customer registration events
3. Process the records asynchronously via outbox handlers
4. Remove both customers after a short delay
5. Schedule and process removal events

## Configuration

See `application.yml` for:
- H2 database configuration
- Outbox schema initialization
- Logging levels

## When to Use This

Use the JDBC starter when:
- You don't need JPA/Hibernate
- You want a lightweight solution
- You prefer manual SQL control
- You have an existing JDBC-based application

This example is perfect for getting started with Namastack Outbox using pure JDBC without the overhead of JPA.

