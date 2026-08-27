---
title: Handlers
description: Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.
sidebar_position: 3
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Handlers

## Handler Types & Interfaces

The library provides two complementary handler interfaces for different use cases:

### Typed Handlers (Type-Safe)

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedRecord> {
    override fun getTypedHandlerIdentity() =
        OutboxHandlerIdentity(id = "orders.publish-created")

    override fun handle(payload: OrderCreatedRecord, metadata: OutboxRecordMetadata) {
        println("Processing order: ${payload.orderId}")
        eventPublisher.publish(payload)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderCreatedHandler implements OutboxTypedHandler<OrderCreatedRecord> {
    @Override
    public OutboxHandlerIdentity getTypedHandlerIdentity() {
        return new OutboxHandlerIdentity("orders.publish-created");
    }

    @Override
    public void handle(OrderCreatedRecord payload, OutboxRecordMetadata metadata) {
        System.out.println("Processing order: " + payload.getOrderId());
        eventPublisher.publish(payload);
    }
}
```

</TabItem>
</Tabs>

## Generic Handlers (Multi-Type)

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class UniversalHandler : OutboxHandler {
    override fun getGenericHandlerIdentity() =
        OutboxHandlerIdentity(id = "events.publish-generic")

    override fun handle(payload: Any, metadata: OutboxRecordMetadata) {
        when (payload) {
            is OrderCreatedRecord -> handleOrder(payload)
            is PaymentProcessedRecord -> handlePayment(payload)
            is CreateCustomerCommand -> createCustomer(payload)
            else -> logger.warn("Unknown payload: ${payload::class.simpleName}")
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class UniversalHandler implements OutboxHandler {
    @Override
    public OutboxHandlerIdentity getGenericHandlerIdentity() {
        return new OutboxHandlerIdentity("events.publish-generic");
    }

    @Override
    public void handle(Object payload, OutboxRecordMetadata metadata) {
        if (payload instanceof OrderCreatedRecord) {
            handleOrder((OrderCreatedRecord) payload);
        } else if (payload instanceof PaymentProcessedRecord) {
            handlePayment((PaymentProcessedRecord) payload);
        } else if (payload instanceof CreateCustomerCommand) {
            createCustomer((CreateCustomerCommand) payload);
        } else {
            logger.warn("Unknown payload: {}", payload.getClass().getSimpleName());
        }
    }
}
```

</TabItem>
</Tabs>

### Handler Invocation Order

When multiple handlers are registered:

1. **All matching typed handlers** are invoked first (in registration order)
2. **All generic handlers** are invoked second (catch-all)

---

## Annotation-based Handlers

Use `@OutboxHandler` annotation for method-level handler registration as an alternative to implementing interfaces:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class MyHandlers {
    @OutboxHandler(id = "orders.publish-created")
    fun handleOrderCreated(payload: OrderCreatedRecord) {
        // ...
    }

    @OutboxHandler(id = "payments.publish-processed")
    fun handlePaymentProcessed(payload: PaymentProcessedRecord) {
        // ...
    }
    
    @OutboxHandler(id = "events.publish-generic")
    fun handleAny(payload: Any, metadata: OutboxRecordMetadata) {
        // Generic handler via annotation
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class MyHandlers {
    @OutboxHandler(id = "orders.publish-created")
    public void handleOrderCreated(OrderCreatedRecord payload) {
        // ...
    }

    @OutboxHandler(id = "payments.publish-processed")
    public void handlePaymentProcessed(PaymentProcessedRecord payload) {
        // ...
    }
    
    @OutboxHandler(id = "events.publish-generic")
    public void handleAny(Object payload, OutboxRecordMetadata metadata) {
        // Generic handler via annotation
    }
}
```

</TabItem>
</Tabs>

:::note Handler Signature Requirements

- **Typed handlers** can accept 1 or 2 parameters:
    - `fun handle(payload: T)` - Payload only
    - `fun handle(payload: T, metadata: OutboxRecordMetadata)` - Payload + metadata
- **Generic handlers** must accept 2 parameters:
    - `fun handle(payload: Any, metadata: OutboxRecordMetadata)` - Required signature

**Interface vs Annotation:**

- **Interfaces**: Best when entire class is dedicated to handling a single type
- **Annotations**: Best when a class handles multiple types or mixing with other logic
:::

---

## Stable Handler Identities

The handler ID is persisted on every outbox record and later used to route that record to its
handler. It is therefore a durable contract between the database and the application, not merely a
runtime implementation detail.

**Define an explicit, stable ID for production handlers.** Prefer a name that describes the
handler's logical responsibility, such as `orders.publish-created`, and keep it independent of Java
or Kotlin package, class, method, and parameter names. Explicit handler IDs and aliases must be
non-blank and unique across the application.

When no explicit ID is configured, Namastack Outbox remains backward compatible and generates an ID
from the handler's class, method, and parameter types. Lambda handlers use their Spring bean name.
Generated IDs are convenient for prototypes, but refactoring any participating name or type can
leave pending records pointing to an ID that a later deployment no longer provides.

### Annotation-based Identity

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@OutboxHandler(
    id = "orders.publish-created",
    aliases = ["orders.send-created"]
)
fun handleOrderCreated(payload: OrderCreatedRecord) {
    eventPublisher.publish(payload)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@OutboxHandler(
    id = "orders.publish-created",
    aliases = {"orders.send-created"}
)
public void handleOrderCreated(OrderCreatedRecord payload) {
    eventPublisher.publish(payload);
}
```

</TabItem>
</Tabs>

### Interface-based Identity

Return an `OutboxHandlerIdentity` from the role-specific identity method. Typed handlers use
`getTypedHandlerIdentity()` and generic handlers use `getGenericHandlerIdentity()`.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedRecord> {
    override fun getTypedHandlerIdentity() =
        OutboxHandlerIdentity(
            id = "orders.publish-created",
            aliases = setOf("orders.send-created")
        )

    override fun handle(payload: OrderCreatedRecord, metadata: OutboxRecordMetadata) {
        eventPublisher.publish(payload)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderCreatedHandler implements OutboxTypedHandler<OrderCreatedRecord> {
    @Override
    public OutboxHandlerIdentity getTypedHandlerIdentity() {
        return new OutboxHandlerIdentity(
            "orders.publish-created",
            Set.of("orders.send-created")
        );
    }

    @Override
    public void handle(OrderCreatedRecord payload, OutboxRecordMetadata metadata) {
        eventPublisher.publish(payload);
    }
}
```

</TabItem>
</Tabs>

Aliases are lookup-only: they resolve existing records but are never written to new records and do
not create additional handler invocations. Use an alias only when both IDs represent the same
logical handler and a compatible payload contract. For an incompatible change in meaning or payload,
introduce a new handler ID instead.

### Migrating an ID Safely

Changing an explicit ID during a rolling deployment requires two releases so that old and new
application instances understand every ID that may be written:

1. Keep the current ID canonical and add the future ID as an alias. Deploy this version everywhere.
2. Promote the future ID to canonical and retain the previous ID as an alias.
3. Remove the old alias only after no record can still contain it and no old application instance is
   running.

For example, release one uses:

```kotlin
@OutboxHandler(
    id = "orders.send-created",
    aliases = ["orders.publish-created"]
)
```

Release two then uses:

```kotlin
@OutboxHandler(
    id = "orders.publish-created",
    aliases = ["orders.send-created"]
)
```

The same two-release approach is recommended when moving from a generated ID to an explicit ID:
first keep the generated ID by omitting `id` and add the future ID as an alias; in the next release,
set the explicit ID. The generated ID is then retained automatically as a legacy alias where it can
be reconstructed reliably.

---

## OutboxRecordMetadata

Handlers that accept `OutboxRecordMetadata` receive processing metadata for the current
handler invocation:

- `key` - Logical record key used for ordered processing
- `handlerId` - Handler assigned to the record
- `createdAt` - Time when the outbox record was created
- `context` - Propagated context such as trace IDs or tenant IDs
- `failureCount` - Number of failed processing attempts before this invocation
- `attempt` - One-based attempt number, equal to `failureCount + 1`
- `isRetry` - `true` when `failureCount > 0`

For normal handlers, `failureCount == 0` means the first delivery attempt. A retry is
processed by the same `@OutboxHandler` with `failureCount > 0`.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@OutboxHandler(id = "orders.send-email")
fun handleOrder(payload: OrderEvent, metadata: OutboxRecordMetadata) {
    if (metadata.isRetry) {
        logger.info("Retrying order ${payload.orderId} on attempt ${metadata.attempt}")
    }
    emailService.send(payload.email)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@OutboxHandler(id = "orders.send-email")
public void handleOrder(OrderEvent payload, OutboxRecordMetadata metadata) {
    if (metadata.isRetry()) {
        logger.info("Retrying order {} on attempt {}", payload.getOrderId(), metadata.getAttempt());
    }
    emailService.send(payload.getEmail());
}
```

</TabItem>
</Tabs>

---

## Fallback Handlers

Fallback handlers provide a safety net when all retries are exhausted, allowing for compensating actions, dead letter queue publishing, or alternative processing strategies.

Fallback handlers are automatically invoked when:

- **Retries Exhausted**: The record has exceeded the maximum retry count
- **Non-Retryable Exceptions**: An exception is thrown that should not be retried (based on retry policy)

Fallback handlers are not invoked for each retry. Retry attempts are processed by the normal
handler; fallback handlers run only when normal processing can no longer continue.

### Interface-Based Fallback Handlers

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderFallbackHandler : OutboxFallbackHandler<OrderEvent> {
    override fun handle(payload: OrderEvent, context: OutboxFailureContext) {
        logger.error(
            "Order ${payload.orderId} failed permanently after ${context.failureCount} attempts",
            context.lastException
        )
        // Publish to dead letter queue
        deadLetterQueue.publish(
            payload = payload,
            reason = "Max retries exceeded",
            exception = context.lastException,
            traceId = context.context["traceId"]
        )
        // Send alert
        alertService.sendAlert(
            "Order processing failed permanently: ${payload.orderId}"
        )
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderFallbackHandler implements OutboxFallbackHandler<OrderEvent> {
    @Override
    public void handle(OrderEvent payload, OutboxFailureContext context) {
        logger.error(
            "Order {} failed permanently after {} attempts",
            payload.getOrderId(),
            context.getFailureCount(),
            context.getLastException()
        );
        // Publish to dead letter queue
        deadLetterQueue.publish(
            payload,
            "Max retries exceeded",
            context.getLastException(),
            context.getContext().get("traceId")
        );
        // Send alert
        alertService.sendAlert(
            "Order processing failed permanently: " + payload.getOrderId()
        );
    }
}
```

</TabItem>
</Tabs>

### Annotation-Based Fallback Handlers

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderHandlers {
    @OutboxHandler(id = "orders.send-email")
    fun handleOrder(payload: OrderEvent) {
        emailService.send(payload.email)  // May fail
    }
    @OutboxFallbackHandler
    fun handleOrderFailure(payload: OrderEvent, context: OutboxFailureContext) {
        logger.error(
            "Order ${payload.orderId} failed after ${context.failureCount} attempts"
        )
        deadLetterQueue.publish(payload)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderHandlers {
    @OutboxHandler(id = "orders.send-email")
    public void handleOrder(OrderEvent payload) {
        emailService.send(payload.getEmail());  // May fail
    }
    @OutboxFallbackHandler
    public void handleOrderFailure(OrderEvent payload, OutboxFailureContext context) {
        logger.error(
            "Order {} failed after {} attempts",
            payload.getOrderId(),
            context.getFailureCount()
        );
        deadLetterQueue.publish(payload);
    }
}
```

</TabItem>
</Tabs>

### OutboxFailureContext

The `OutboxFailureContext` provides comprehensive failure information:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
interface OutboxFailureContext {
    val handlerId: String              // Handler that failed
    val recordId: String               // Unique identifier of the record
    val recordKey: String              // Record key
    val createdAt: Instant             // When record was created
    val failureCount: Int              // Number of failed attempts
    val lastFailure: Throwable?        // Last exception thrown
    val retriesExhausted: Boolean      // True if retry limit was reached
    val nonRetryableException: Boolean // True if failure was due to non-retryable exception
    val context: Map<String, String>   // Propagated context (traceId, tenantId, etc.)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public interface OutboxFailureContext {
    String getHandlerId();             // Handler that failed
    String getRecordId();              // Unique identifier of the record
    String getRecordKey();             // Record key
    Instant getCreatedAt();            // When record was created
    int getFailureCount();             // Number of failed attempts
    Throwable getLastFailure();        // Last exception thrown
    boolean isRetriesExhausted();      // True if retry limit was reached
    boolean isNonRetryableException(); // True if failure was due to non-retryable exception
    Map<String, String> getContext();  // Propagated context (traceId, tenantId, etc.)
}
```

</TabItem>
</Tabs>

### Fallback Behavior

**Record Status After Fallback:**

- **Fallback Succeeds**: Record marked as `COMPLETED`
- **Fallback Fails**: Record marked as `FAILED` (requires manual intervention)

**Automatic Matching:**

Fallback handlers are automatically matched to primary handlers by payload type. One fallback handler can serve multiple primary handlers processing the same payload type.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderHandlers {
    // Both handlers share the same fallback
    @OutboxHandler(id = "orders.create")
    fun handleOrderCreated(payload: OrderEvent) {
        orderService.create(payload)
    }
    @OutboxHandler(id = "orders.update")
    fun handleOrderUpdated(payload: OrderEvent) {
        orderService.update(payload)
    }
    @OutboxFallbackHandler
    fun handleOrderFailure(payload: OrderEvent, context: OutboxFailureContext) {
        // Handles failures from both handleOrderCreated and handleOrderUpdated
        deadLetterQueue.publish(payload)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderHandlers {
    // Both handlers share the same fallback
    @OutboxHandler(id = "orders.create")
    public void handleOrderCreated(OrderEvent payload) {
        orderService.create(payload);
    }
    @OutboxHandler(id = "orders.update")
    public void handleOrderUpdated(OrderEvent payload) {
        orderService.update(payload);
    }
    @OutboxFallbackHandler
    public void handleOrderFailure(OrderEvent payload, OutboxFailureContext context) {
        // Handles failures from both handleOrderCreated and handleOrderUpdated
        deadLetterQueue.publish(payload);
    }
}
```

</TabItem>
</Tabs>

:::warning Fallback Handler Requirements
- Only **one fallback handler per payload type** is supported
- Fallback handlers must match the payload type exactly
- Fallback signature: `fun handle(payload: T, context: OutboxFailureContext)`
:::

### Fallback Use Cases

**Common use cases for fallback handlers:**

1. **Dead Letter Queue**: Publish failed records to a DLQ for later analysis
2. **Alert & Monitoring**: Send alerts when records fail permanently
3. **Compensating Actions**: Execute compensating transactions (e.g., refund, rollback)
4. **Alternative Processing**: Route to alternative processing logic
5. **Audit Logging**: Log failure details for compliance and debugging
