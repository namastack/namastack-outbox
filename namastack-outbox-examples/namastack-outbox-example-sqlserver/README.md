# Namastack Outbox - SQL Server Example

This example demonstrates using Namastack Outbox with **Microsoft SQL Server database**.

## What This Example Shows

- Outbox pattern with SQL Server as the persistence layer
- SQL Server-specific configuration and setup
- Production-ready database setup with Docker
- Flyway migrations for SQL Server

## Prerequisites

Start SQL Server using Docker Compose:

```bash
docker-compose up -d
```

This will start a SQL Server instance on port 1433.

**Note**: SQL Server requires acceptance of the EULA. By running this example, you accept the [SQL Server EULA](https://go.microsoft.com/fwlink/?linkid=857698).

## Key Components

- **SQL Server Database**: Microsoft's enterprise relational database
- **Docker Compose**: Easy SQL Server setup for development
- **Flyway Migrations**: Database schema management
- **Standard Handlers**: Same handler implementation as other examples

## Database Configuration

```yaml
datasource:
  url: jdbc:sqlserver://localhost;databaseName=outbox_example;encrypt=true;trustServerCertificate=true
  username: username
  password: "ThisIsAComplexPassw0rd123!"
```

## Running the Example

1. Start SQL Server:
   ```bash
   docker-compose up -d
   ```

2. Wait for SQL Server to initialize (first start can take a minute)

3. Run the application:
   ```bash
   ./gradlew :namastack-outbox-example-sqlserver:bootRun
   ```

4. Stop SQL Server:
   ```bash
   docker-compose down
   ```

## What Happens

The application will:
1. Connect to SQL Server database
2. Initialize outbox tables via Flyway
3. Register customers and schedule outbox records
4. Process records asynchronously
5. Persist all data in SQL Server

## Configuration

See `application.yml` for:
- SQL Server connection settings
- JPA/Hibernate configuration for SQL Server
- Outbox schema initialization

This example demonstrates production-ready SQL Server integration with the outbox pattern.

