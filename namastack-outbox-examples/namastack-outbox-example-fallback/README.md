# Namastack Outbox - Fallback Handler Example

This example demonstrates **fallback handler functionality** for handling permanent failures after retries are exhausted.

## What This Example Shows

- Using `@OutboxFallbackHandler` annotation to define fallback logic
- Using `OutboxHandlerWithFallback` for a generic interface-based handler and its fallback logic
- Automatic fallback invocation when primary handler fails and retries are exhausted
- Access to failure context including exception details and retry information
- Graceful degradation when primary processing fails

## Key Components

- **CustomerRegisteredOutboxHandler**: Contains both primary and fallback handler methods:
  - `@OutboxHandler`: Primary handler that intentionally fails
  - `@OutboxFallbackHandler`: Fallback handler invoked after failures
- **GenericOutboxHandler**: Implements `OutboxHandlerWithFallback` to handle every payload type and
  define its fallback in the same class
- **OutboxFailureContext**: Provides failure information (exception, retry count, etc.)
- **Simulated Failures**: Handler always throws exceptions to demonstrate fallback behavior

## Handler Setup

```kotlin
@Component
class CustomerRegisteredOutboxHandler {
    @OutboxHandler(id = "customers.send-registration-email")
    fun handle(payload: CustomerRegisteredEvent) {
        // Primary handler - fails intentionally
        throw RuntimeException("Simulated failure")
    }

    @OutboxFallbackHandler
    fun handleFailure(payload: CustomerRegisteredEvent, context: OutboxFailureContext) {
        // Fallback logic - invoked after retries exhausted
        logger.info("Fallback invoked: ${context}")
    }
}
```

The generic handler uses the interface-based API:

```kotlin
@Component
class GenericOutboxHandler : OutboxHandlerWithFallback {
    override fun getGenericHandlerIdentity() =
        OutboxHandlerIdentity("events.publish-to-external-broker")

    override fun handle(payload: Any, metadata: OutboxRecordMetadata) {
        // Generic primary handler - fails intentionally
        throw RuntimeException("Simulated failure")
    }

    override fun handleFailure(payload: Any, context: OutboxFailureContext) {
        // Generic fallback logic - invoked after retries are exhausted
        logger.info("Fallback invoked: $context")
    }
}
```

## Running the Example

```bash
./gradlew :namastack-outbox-example-fallback:bootRun
```

The application will:
1. Register two customers
2. Schedule outbox records
3. Attempt to process via primary handler (fails)
4. Retry processing based on retry configuration
5. Invoke fallback handler after retries are exhausted
6. Mark records as completed after successful fallback

## Configuration

See `application.yml` for:
- Retry policy configuration
- Maximum retry attempts
- Fallback behavior settings

This example is essential for understanding how to handle permanent failures gracefully in production systems.

## Stable handler IDs

The handlers define responsibility-based IDs that remain stable across class, method, and package
renames:

- `customers.send-registration-email` for the typed customer-registration handler
- `events.publish-to-external-broker` for the generic event publisher
