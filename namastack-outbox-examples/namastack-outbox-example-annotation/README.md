# Namastack Outbox - Annotation-Based Handler Example
This example is ideal for understanding annotation-based handler registration as an alternative to interface-based handlers.

See `application.yml` for outbox and database configuration.

## Configuration

4. Remove customers and process removal events
3. Process records via annotated handler methods
2. Schedule outbox records
1. Register two customers
The application will:

```
./gradlew :namastack-outbox-example-annotation:bootRun
```bash

## Running the Example

```
}
    }
        // Handle specific event type
    fun handle(payload: CustomerRegisteredEvent) {
    @OutboxHandler
    // Typed handler - processes only CustomerRegisteredEvent

    }
        // Handle any payload
    fun handle(payload: Any, metadata: OutboxRecordMetadata) {
    @OutboxHandler
    // Generic handler - processes all event types
class DemoOutboxHandler {
@Component
```kotlin

## Handler Registration

- **H2 Database**: In-memory database for quick testing
- **CustomerService**: Schedules outbox records during customer registration/removal
  - Typed handler: Processes only `CustomerRegisteredEvent` payloads
  - Generic handler: Processes any payload type with metadata
- **DemoOutboxHandler**: Contains multiple `@OutboxHandler` annotated methods:

## Key Components

- Flexible handler registration without interface constraints
- Both typed and generic handler methods with annotations
- Multiple handler methods in a single class
- Using `@OutboxHandler` annotation on methods instead of implementing interfaces

## What This Example Shows

This example demonstrates **annotation-based handler registration** using `@OutboxHandler` annotations.


