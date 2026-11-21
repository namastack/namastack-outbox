# Namastack Outbox - PostgreSQL Example

This example demonstrates using Namastack Outbox with **PostgreSQL database**.

## What This Example Shows

- Outbox pattern with PostgreSQL as the persistence layer
- PostgreSQL-specific configuration and setup
- Production-ready database setup with Docker
- Flyway migrations for PostgreSQL

## Prerequisites

Start PostgreSQL using Docker Compose:

```bash
docker-compose up -d
```

This will start a PostgreSQL instance on port 5432.

## Key Components

- **PostgreSQL Database**: Advanced open-source relational database
- **Docker Compose**: Easy PostgreSQL setup for development
- **Flyway Migrations**: Database schema management
- **Standard Handlers**: Same handler implementation as other examples

## Database Configuration

```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/outbox_example
  username: username
  password: password
```

## Running the Example

1. Start PostgreSQL:
   ```bash
   docker-compose up -d
   ```

2. Run the application:
   ```bash
   ./gradlew :namastack-outbox-example-postgresql:bootRun
   ```

3. Stop PostgreSQL:
   ```bash
   docker-compose down
   ```

## Testing retries

[`ExamplePostgresqlRetryTest`](src/test/kotlin/io/namastack/demo/ExamplePostgresqlRetryTest.kt) uses
Spring Boot and a PostgreSQL Testcontainer. The retry test loads the JDBC starter with a single
test-only handler and schedules one `RetryEvent` in a committed transaction. `FailOnceHandler`
records the metadata from each invocation, throws an exception on the first invocation, and stores
the successfully processed event on the next one.

Awaitility waits for that event. The test checks that both invocations have the same non-blank
`recordId`, that `attempt` progresses from `1` to `2`, and that `isRetry` changes from `false` to
`true`. This verifies that the outbox retries the same persisted record after a transient handler
failure.

Run these commands from the repository root with Docker running. Use JDK 21 to run Gradle and make
JDK 17 available for the build toolchain. Testcontainers starts PostgreSQL for the tests.

```bash
./gradlew publishToMavenLocal -x test --no-daemon
cd namastack-outbox-examples/namastack-outbox-example-postgresql
./gradlew test --rerun --no-daemon
```

## What Happens

The application will:
1. Connect to PostgreSQL database
2. Initialize outbox tables via Flyway
3. Register customers and schedule outbox records
4. Process records asynchronously
5. Persist all data in PostgreSQL

## Configuration

See `application.yml` for:
- PostgreSQL connection settings
- JPA/Hibernate configuration for PostgreSQL
- Outbox schema initialization

This example demonstrates production-ready PostgreSQL integration with the outbox pattern.

## Stable handler IDs

The handlers define responsibility-based IDs that remain stable across class, method, and package
renames:

- `customers.send-registration-email` for the typed customer-registration handler
- `events.publish-to-external-broker` for the generic event publisher
