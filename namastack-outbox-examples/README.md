# Namastack Outbox - Examples

This directory contains various example projects demonstrating different features and use cases of the Namastack Outbox library.

## Available Examples

### 🚀 Getting Started

- **[namastack-outbox-example-h2](namastack-outbox-example-h2)** - Basic outbox pattern setup with H2 in-memory database using JPA. Perfect starting point.

### 🎯 Handler Registration

- **[namastack-outbox-example-annotation](namastack-outbox-example-annotation)** - Annotation-based handler registration using `@OutboxHandler`

### 🔄 Resilience & Error Handling

- **[namastack-outbox-example-retry](namastack-outbox-example-retry)** - Retry policies and automatic retry behavior
- **[namastack-outbox-example-fallback](namastack-outbox-example-fallback)** - Fallback handlers for permanent failures using `@OutboxFallbackHandler`

### 🔌 Integration Patterns

- **[namastack-outbox-example-kafka](namastack-outbox-example-kafka)** - Externalizing outbox records to Apache Kafka (Kotlin)
- **[namastack-outbox-example-kafka-java](namastack-outbox-example-kafka-java)** - Externalizing outbox records to Apache Kafka (Java)
- **[namastack-outbox-example-rabbit](namastack-outbox-example-rabbit)** - Externalizing outbox records to RabbitMQ (Kotlin)
- **[namastack-outbox-example-rabbit-java](namastack-outbox-example-rabbit-java)** - Externalizing outbox records to RabbitMQ (Java)
- **[namastack-outbox-example-multicaster](namastack-outbox-example-multicaster)** - Transaction isolation between Spring events and outbox
- **[namastack-outbox-example-tracing](namastack-outbox-example-tracing)** - Distributed tracing with OpenTelemetry and Micrometer

### 💾 Database Support

- **[namastack-outbox-example-jdbc](namastack-outbox-example-jdbc)** - JDBC-based implementation with H2 (no JPA/Hibernate required)
- **[namastack-outbox-example-mysql](namastack-outbox-example-mysql)** - MySQL database integration
- **[namastack-outbox-example-mariadb](namastack-outbox-example-mariadb)** - MariaDB database integration
- **[namastack-outbox-example-postgresql](namastack-outbox-example-postgresql)** - PostgreSQL database integration
- **[namastack-outbox-example-sqlserver](namastack-outbox-example-sqlserver)** - SQL Server database integration

### 🏷️ Table Naming & Schema Management

- **[namastack-outbox-example-flyway-jdbc](namastack-outbox-example-flyway-jdbc)** - JDBC example with **Flyway migrations** for production-ready manual schema management
- **[namastack-outbox-example-flyway-jpa](namastack-outbox-example-flyway-jpa)** - JPA example with **Flyway migrations** for production-ready manual schema management (`ddl-auto: validate`)
- **[namastack-outbox-example-table-prefix-jpa](namastack-outbox-example-table-prefix-jpa)** - JPA example showing how to use a **custom H2 schema** and **table prefixes** via Hibernate's `PhysicalNamingStrategy`
- **[namastack-outbox-example-table-prefix-jdbc](namastack-outbox-example-table-prefix-jdbc)** - JDBC example showing how to use **custom table prefixes** and **custom database schemas** with manual schema creation

### ☕ Language Support

- **[namastack-outbox-example-java](namastack-outbox-example-java)** - Pure Java implementation (no Kotlin required)
- **[namastack-outbox-example-kafka-java](namastack-outbox-example-kafka-java)** - Kafka integration with Java builder API

## Quick Start

Each example is a standalone Spring Boot application. To run any example:

```bash
# Navigate to the example directory
cd namastack-outbox-example-h2

# Run using Gradle
./gradlew bootRun
```

For database examples (MySQL, PostgreSQL, etc.), start the required database first:

```bash
# In the example directory
docker-compose up -d

# Then run the application
./gradlew bootRun
```

## Example Structure

All examples follow a similar structure:

```
namastack-outbox-example-*/
├── README.md                    # Detailed example documentation
├── build.gradle.kts             # Gradle build configuration
├── docker-compose.yml           # Database setup (if applicable)
└── src/
    └── main/
        ├── kotlin/ (or java/)   # Application code
        │   └── io/namastack/demo/
        │       ├── DemoApplication.kt
        │       ├── *Handler.kt   # Outbox handlers
        │       └── customer/     # Domain model
        └── resources/
            └── application.yml   # Configuration
```

## Learning Path

Recommended order for learning:

1. **Start with basics**: [example-h2](namastack-outbox-example-h2) - Understand core concepts
2. **Handler styles**: [example-annotation](namastack-outbox-example-annotation) - Learn annotation-based handlers
3. **Error handling**: [example-retry](namastack-outbox-example-retry) - See retry mechanisms
4. **Fallback logic**: [example-fallback](namastack-outbox-example-fallback) - Handle permanent failures
5. **Advanced patterns**: [example-tracing](namastack-outbox-example-tracing) - Distributed tracing
6. **Production databases**: Choose your database example (MySQL, PostgreSQL, etc.)

## Requirements

- Java 21 or later
- Docker & Docker Compose (for database examples)
- Gradle (wrapper included)

## Support

Each example contains a detailed README with:
- What the example demonstrates
- Key components and concepts
- Step-by-step running instructions
- Expected behavior and output
- Configuration details

For general library documentation, see the [main project README](../README.md).
