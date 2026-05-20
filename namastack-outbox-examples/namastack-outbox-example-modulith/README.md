# Spring Modulith Kafka Example

This example demonstrates Spring Modulith event externalization to Apache Kafka.

## What This Example Shows

- A Spring Modulith application with two modules: `order` and `payment`
- `OrderService` persists an order and publishes an internal `OrderPlacedEvent`
- The `payment` module reacts with `@ApplicationModuleListener`
- `PaymentRequestedEvent` is marked with Spring Modulith's `@Externalized`
- Spring Modulith `2.1.0-RC1` publishes the externalized event to Kafka via `spring-modulith-events-kafka`
- A local mock payment provider consumes the Kafka message and marks the payment as captured
- H2 and JPA provide a self-contained persistence setup

## Key Components

- **Order module**: owns order placement and publishes `OrderPlacedEvent`
- **Payment module**: creates payments and externalizes `PaymentRequestedEvent`
- **ModuleMetadata**: Kotlin-based Spring Modulith metadata in each module root package
- **OrderPlacedEventListener**: payment module listener for internal order events
- **PaymentRequestedEvent**: declares the Kafka topic and key via `@Externalized("payment-requests::#{orderId}")`
- **MockPaymentProviderKafkaListener**: simulated external payment provider consuming from Kafka

## Running the Example

```bash
docker compose up -d
./gradlew bootRun
```

The application will:

1. Create one order
2. Let the payment module create a payment request after the order transaction commits
3. Publish the externalized payment request to Kafka topic `payment-requests`
4. Consume the Kafka message with the mock payment provider
5. Mark the payment as captured

## Important Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
  modulith:
    events:
      externalization:
        enabled: true
```

Spring Modulith's default externalization mode is used. The Kafka module registers the event externalizer automatically when `spring-modulith-events-kafka` and Spring Kafka are on the classpath.
