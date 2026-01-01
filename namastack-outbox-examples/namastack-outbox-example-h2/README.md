# Namastack Outbox - H2 Example

This example demonstrates the basic usage of the Namastack Outbox pattern with an **H2 in-memory database**.

## What This Example Shows

- Basic outbox pattern setup with H2 database
- Scheduling outbox records using `Outbox.schedule()`
- Processing outbox records with typed and generic handlers
- Using `@EnableOutbox` to enable the outbox functionality
- In-memory database configuration for quick testing

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **CustomerRegisteredOutboxHandler**: Typed handler that processes `CustomerRegisteredEvent` payloads
- **GenericOutboxHandler**: Generic handler that processes any payload type
- **H2 Database**: In-memory database for quick setup without external dependencies

## Running the Example

```bash
./gradlew :namastack-outbox-example-h2:bootRun
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

This example is perfect for getting started with Namastack Outbox without setting up external databases.

