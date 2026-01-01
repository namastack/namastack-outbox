# Namastack Outbox - MySQL Example

This example demonstrates using Namastack Outbox with **MySQL database**.

## What This Example Shows

- Outbox pattern with MySQL as the persistence layer
- MySQL-specific configuration and setup
- Production-ready database setup with Docker
- Flyway migrations for MySQL

## Prerequisites

Start MySQL using Docker Compose:

```bash
docker-compose up -d
```

This will start a MySQL instance on port 3306.

## Key Components

- **MySQL Database**: Production-grade relational database
- **Docker Compose**: Easy MySQL setup for development
- **Flyway Migrations**: Database schema management
- **Standard Handlers**: Same handler implementation as other examples

## Database Configuration

```yaml
datasource:
  url: jdbc:mysql://localhost:3306/outbox_example
  username: username
  password: password
```

## Running the Example

1. Start MySQL:
   ```bash
   docker-compose up -d
   ```

2. Run the application:
   ```bash
   ./gradlew :namastack-outbox-example-mysql:bootRun
   ```

3. Stop MySQL:
   ```bash
   docker-compose down
   ```

## What Happens

The application will:
1. Connect to MySQL database
2. Initialize outbox tables via Flyway
3. Register customers and schedule outbox records
4. Process records asynchronously
5. Persist all data in MySQL

## Configuration

See `application.yml` for:
- MySQL connection settings
- JPA/Hibernate configuration for MySQL
- Outbox schema initialization

This example demonstrates production-ready MySQL integration with the outbox pattern.

