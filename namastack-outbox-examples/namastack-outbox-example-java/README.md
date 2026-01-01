# Namastack Outbox - Java Example

This example demonstrates using Namastack Outbox library in a **pure Java application** (no Kotlin).

## What This Example Shows

- Full Java compatibility of the Namastack Outbox library
- Using outbox pattern with Java classes and annotations
- Implementing handlers in Java
- Java-based Spring Boot configuration

## Key Components

- **CustomerService.java**: Java service scheduling outbox records
- **CustomerRegisteredOutboxHandler.java**: Typed handler in Java
- **GenericOutboxHandler.java**: Generic handler in Java
- **All domain classes in Java**: Customer, events, repository

## Handler Implementation

```java
@Component
public class CustomerRegisteredOutboxHandler 
    implements OutboxTypedHandler<CustomerRegisteredEvent> {
    
    @Override
    public void handle(CustomerRegisteredEvent payload, OutboxRecordMetadata metadata) {
        // Handle event in Java
        ExternalMailService.send(payload.getEmail());
    }
}
```

## Running the Example

```bash
./gradlew :namastack-outbox-example-java:bootRun
```

The application will:
1. Register two customers using Java service
2. Schedule outbox records
3. Process records via Java handlers
4. Remove customers and process removal events

## Configuration

See `application.yml` for outbox and database configuration.

## Key Takeaways

- ✅ Namastack Outbox fully supports Java applications
- ✅ All features available in Java (handlers, fallbacks, retry)
- ✅ Interface-based handler registration works seamlessly
- ✅ No Kotlin runtime required for Java-only projects

This example is perfect for Java developers who want to use the outbox pattern without switching to Kotlin.

