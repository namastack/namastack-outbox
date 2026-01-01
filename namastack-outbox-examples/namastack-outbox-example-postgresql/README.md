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

