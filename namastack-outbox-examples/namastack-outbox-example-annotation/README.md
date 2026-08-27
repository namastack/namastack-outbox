# Namastack Outbox - Annotation-Based Handler Example

This example demonstrates annotation-based handler registration as an alternative to
interface-based handlers.

## What This Example Shows

- Using `@OutboxHandler` instead of implementing handler interfaces
- Registering typed and generic handler methods in one class
- Assigning unique, responsibility-based IDs to annotated handlers

## Handler Registration

```kotlin
@Component
class DemoOutboxHandler {
    @OutboxHandler(id = "events.publish-to-external-broker")
    fun handle(payload: Any, metadata: OutboxRecordMetadata) {
        // Handle any payload
    }

    @OutboxHandler(id = "customers.send-registration-email")
    fun handle(payload: CustomerRegisteredEvent) {
        // Handle a specific event type
    }
}
```

The explicit IDs remain stable across class, method, parameter, and package renames. They are
stored with outbox records and used to route those records back to the intended handlers.

## Running the Example

```bash
./gradlew :namastack-outbox-example-annotation:bootRun
```

The application will:

1. Register two customers.
2. Schedule outbox records.
3. Process records via annotated handler methods.
4. Remove the customers and process removal events.

## Configuration

See `application.yml` for outbox and database configuration.
