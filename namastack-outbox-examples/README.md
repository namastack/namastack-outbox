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

- **[namastack-outbox-example-multicaster](namastack-outbox-example-multicaster)** - Transaction isolation between Spring events and outbox
- **[namastack-outbox-example-tracing](namastack-outbox-example-tracing)** - Distributed tracing with OpenTelemetry and Micrometer

### 💾 Database Support

- **[namastack-outbox-example-jdbc](namastack-outbox-example-jdbc)** - JDBC-based implementation with H2 (no JPA/Hibernate required)
- **[namastack-outbox-example-mysql](namastack-outbox-example-mysql)** - MySQL database integration
- **[namastack-outbox-example-mariadb](namastack-outbox-example-mariadb)** - MariaDB database integration
- **[namastack-outbox-example-postgresql](namastack-outbox-example-postgresql)** - PostgreSQL database integration
- **[namastack-outbox-example-sqlserver](namastack-outbox-example-sqlserver)** - SQL Server database integration

### ☕ Language Support

- **[namastack-outbox-example-java](namastack-outbox-example-java)** - Pure Java implementation (no Kotlin required)

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

## Common Configuration

All examples share similar configuration in `application.yml`:

```yaml
outbox:
  schema-initialization:
    enabled: true              # Auto-create outbox tables
  instance:
    graceful-shutdown-timeout-seconds: 2
  retry:
    policy: "fixed"            # or "exponential"
    max-retries: 3
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

