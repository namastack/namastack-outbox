# Namastack Outbox - Kafka Example (Java)

This example demonstrates how to externalize outbox records to **Apache Kafka** using the Namastack Outbox pattern with **pure Java** (no Kotlin).

## What This Example Shows

- Externalizing outbox records to Kafka topics using Java
- Configuring `KafkaOutboxRouting` with the Java builder API
- Using `KafkaOutboxHandler` for automatic Kafka integration
- Routing payloads to different Kafka topics based on type
- Auto-configuration with Spring Kafka

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **KafkaOutboxRoutingConfiguration**: Java-based routing configuration using the builder API
- **KafkaOutboxHandler**: Automatically sends outbox payloads to Kafka topics
- **H2 Database**: In-memory database for outbox storage
- **Spring Kafka**: Producer configuration for sending messages

## Running the Example

### Prerequisites

Start Kafka using Docker Compose:

```bash
docker-compose up -d
```

This starts:
- **Kafka** on port `9092`
- **Kafka UI** on port `8888` (http://localhost:8888)

### Run the Application

```bash
./gradlew :namastack-outbox-example-kafka-java:bootRun
```

The application will:
1. Register two customers (John Wayne and Macy Grey)
2. Schedule outbox records for customer registration events
3. Process the records and send them to Kafka
4. Remove both customers after a short delay
5. Schedule and send removal events to Kafka

## Java Routing Configuration

This example showcases the Java builder API for configuring Kafka routing:

```java
@Bean
public KafkaOutboxRouting kafkaOutboxRouting() {
    return KafkaOutboxRouting.builder()
        .route(OutboxPayloadSelector.type(CustomerRegisteredEvent.class), route -> route
            .target("customer-registrations")
            .key((payload, metadata) -> metadata.getKey())
            .headers((payload, metadata) -> Map.of(
                "CustomerMail", ((CustomerRegisteredEvent) payload).getEmail()
            ))
        )
        .defaults(route -> route
            .target("default-topic")
            .key((payload, metadata) -> metadata.getKey())
            .headers((payload, metadata) -> Map.of(
                "eventType", payload.getClass().getSimpleName()
            ))
        )
        .build();
}
```

## Configuration

See `application.yml` for:
- Kafka bootstrap servers and producer configuration
- Outbox settings

