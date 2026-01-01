# Namastack Outbox - MariaDB Example

This example demonstrates using Namastack Outbox with **MariaDB database**.

## What This Example Shows

- Outbox pattern with MariaDB as the persistence layer
- MariaDB-specific configuration and setup
- Production-ready database setup with Docker
- Flyway migrations for MariaDB

## Prerequisites

Start MariaDB using Docker Compose:

```bash
docker-compose up -d
```

This will start a MariaDB instance on port 33306 (to avoid conflicts with other MySQL instances).

## Key Components

- **MariaDB Database**: MySQL-compatible open-source database
- **Docker Compose**: Easy MariaDB setup for development
- **Flyway Migrations**: Database schema management
- **Standard Handlers**: Same handler implementation as other examples

## Database Configuration

```yaml
datasource:
  url: jdbc:mysql://localhost:33306/outbox_example
  username: username
  password: password
```

Note: MariaDB uses the MySQL JDBC driver.

## Running the Example

1. Start MariaDB:
   ```bash
   docker-compose up -d
   ```

2. Run the application:
   ```bash
   ./gradlew :namastack-outbox-example-mariadb:bootRun
   ```

3. Stop MariaDB:
   ```bash
   docker-compose down
   ```

## What Happens

The application will:
1. Connect to MariaDB database
2. Initialize outbox tables via Flyway
3. Register customers and schedule outbox records
4. Process records asynchronously
5. Persist all data in MariaDB

## Configuration

See `application.yml` for:
- MariaDB connection settings
- JPA/Hibernate configuration for MariaDB
- Outbox schema initialization

This example demonstrates production-ready MariaDB integration with the outbox pattern.

