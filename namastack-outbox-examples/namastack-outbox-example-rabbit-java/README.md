# Namastack Outbox - Rabbit Example (Java)

This example demonstrates how to externalize outbox records to **RabbitMQ** using the Namastack Outbox pattern with **pure Java** (no Kotlin).

## What This Example Shows

- Externalizing outbox records to RabbitMQ exchanges using Java
- Configuring `RabbitOutboxRouting` with the Java builder API
- Using `RabbitOutboxHandler` for automatic Rabbit integration
- Routing payloads to different exchanges based on type
- Auto-configuration with Spring AMQP

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **RabbitOutboxRoutingConfiguration**: Java-based routing configuration using the builder API
- **RabbitOutboxHandler**: Automatically sends outbox payloads to RabbitMQ
- **H2 Database**: In-memory database for outbox storage
- **Spring AMQP**: RabbitTemplate/RabbitMessageOperations configuration for sending messages

## Running the Example

### Prerequisites

Start RabbitMQ using Docker Compose:

```bash
docker-compose up -d
```

This starts:
- **RabbitMQ** on port `5672`
- **RabbitMQ Management UI** on port `15672` (http://localhost:15672, default user/pass: `guest`/`guest`)

### Run the Application

```bash
./gradlew :namastack-outbox-example-rabbit-java:bootRun
```

The application will:
1. Register two customers (John Wayne and Macy Grey)
2. Schedule outbox records for customer registration events
3. Process the records and send them to RabbitMQ
4. Remove both customers after a short delay
5. Schedule and send removal events

## Java Routing Configuration

This example showcases the Java builder API for configuring Rabbit routing:

```java
@Bean
public RabbitOutboxRouting rabbitOutboxRouting() {
    return RabbitOutboxRouting.builder()
        .route(OutboxPayloadSelector.type(CustomerRegisteredEvent.class), route -> {
            route.target("customer-registrations");
            route.key((payload, metadata) -> metadata.getKey());
            route.headers((payload, metadata) -> Map.of(
                "CustomerMail", ((CustomerRegisteredEvent) payload).getEmail()
            ));
        })
        .defaults(route -> {
            route.target("default-exchange");
            route.key((payload, metadata) -> metadata.getKey());
            route.headers((payload, metadata) -> Map.of(
                "eventType", payload.getClass().getSimpleName()
            ));
        })
        .build();
}
```

## Configuration

See `application.yml` for:
- RabbitMQ connection settings
- Outbox settings (`namastack.outbox.rabbit.default-exchange`)
