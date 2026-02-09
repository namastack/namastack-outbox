# Namastack Outbox - Rabbit Example

This example demonstrates how to externalize outbox records to **RabbitMQ** using the Namastack Outbox pattern.

## What This Example Shows

- Externalizing outbox records to RabbitMQ exchanges
- Using `RabbitOutboxHandler` for automatic Rabbit integration
- Routing payloads to exchanges
- Auto-configuration with Spring AMQP

## Key Components

- **CustomerService**: Demonstrates transactional outbox scheduling when registering and removing customers
- **RabbitOutboxHandler**: Automatically sends outbox payloads to RabbitMQ
- **H2 Database**: In-memory database for outbox storage
- **Spring AMQP**: RabbitTemplate configuration for sending messages

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
./gradlew :namastack-outbox-example-rabbit:bootRun
```

The application will:
1. Register two customers (John Wayne and Macy Grey)
2. Schedule outbox records for customer registration events
3. Process the records and send them to RabbitMQ
4. Remove both customers after a short delay
5. Schedule and send removal events

## Configuration

See `application.yml` for:
- RabbitMQ connection settings
- H2 database configuration
- Outbox settings (`namastack.outbox.rabbit.default-exchange`)
- Logging levels

This example shows how to integrate Namastack Outbox with RabbitMQ for reliable event streaming.
