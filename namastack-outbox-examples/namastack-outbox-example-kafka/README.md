# Namastack Outbox - Kafka Example

This example demonstrates how to externalize outbox records to **Apache Kafka** using the Namastack Outbox pattern.

## What This Example Shows

- Externalizing outbox records to Kafka topics
- Configuring Kafka producer with JSON serialization
- Using `KafkaOutboxHandler` for automatic Kafka integration
- Routing payloads to Kafka topics
- Auto-configuration with Spring Kafka

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
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
./gradlew :namastack-outbox-example-kafka:bootRun
```

The application will:
1. Register two customers (John Wayne and Macy Grey)
2. Schedule outbox records for customer registration events
3. Process the records and send them to Kafka
4. Remove both customers after a short delay
5. Schedule and send removal events to Kafka

## Configuration

See `application.yml` for:
- Kafka bootstrap servers and producer configuration
- H2 database configuration
- Outbox schema initialization
- Logging levels

This example shows how to integrate Namastack Outbox with Apache Kafka for reliable event streaming.

