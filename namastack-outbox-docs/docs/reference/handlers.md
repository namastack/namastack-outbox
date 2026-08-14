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
    @OutboxHandler
    fun handleOrderCreated(payload: OrderCreatedRecord) {
        // ...
    }

    @OutboxHandler
    fun handlePaymentProcessed(payload: PaymentProcessedRecord) {
        // ...
    }
    
    @OutboxHandler
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
    @OutboxHandler
    public void handleOrderCreated(OrderCreatedRecord payload) {
        // ...
    }

    @OutboxHandler
    public void handlePaymentProcessed(PaymentProcessedRecord payload) {
        // ...
    }
    
    @OutboxHandler
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

## Stable Handler IDs and Aliases

Namastack stores the handler ID on every outbox record so that the record can be routed to the
same handler later. By default, this ID is generated from the handler's target class and method
signature. This requires no configuration, but renaming or moving a handler changes its generated
ID while older records may still reference the previous one.

Use an explicit ID when the logical identity of a handler should remain stable across such
refactorings. New records persist the explicit ID; aliases are used only when resolving existing
records.

### Annotation-based Handlers

Set `id` on each annotated handler method. This also allows multiple methods in the same class to
have independent stable IDs.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderHandlers {
    @OutboxHandler(id = "order-created")
    fun handleOrderCreated(payload: OrderCreatedRecord) {
        // ...
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderHandlers {
    @OutboxHandler(id = "order-created")
    public void handleOrderCreated(OrderCreatedRecord payload) {
        // ...
    }
}
```

</TabItem>
</Tabs>

### Interface-based Handlers

Override `getHandlerId()` on `OutboxHandler` or `OutboxTypedHandler`. The fallback variants inherit
the same identifier contract.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedRecord> {
    override fun getHandlerId(): String = "order-created"

    override fun handle(payload: OrderCreatedRecord, metadata: OutboxRecordMetadata) {
        // ...
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class OrderCreatedHandler implements OutboxTypedHandler<OrderCreatedRecord> {
    @Override
    public String getHandlerId() {
        return "order-created";
    }

    @Override
    public void handle(OrderCreatedRecord payload, OutboxRecordMetadata metadata) {
        // ...
    }
}
```

</TabItem>
</Tabs>

Leaving the annotation ID empty or returning `null` from `getHandlerId()` retains the generated
class-and-method ID.

### Migrating Existing Handlers

When an existing handler adopts an explicit ID, Namastack automatically registers its previous
generated target-class ID as an alias. For proxied handlers, the historical runtime proxy-class ID
is registered as an additional alias. Records queued before the change can therefore continue to
resolve the handler, while newly scheduled records use the explicit ID.

If the class or method was already renamed, Namastack cannot derive the old identifier. Declare it
explicitly as an alias instead:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@OutboxHandler(
    id = "order-created",
    aliases = ["com.example.LegacyOrderHandler#handle(com.example.OrderCreatedRecord)"],
)
fun handleOrderCreated(payload: OrderCreatedRecord) {
    // ...
}
```

For interface-based handlers, override `getHandlerAliases()`:

```kotlin
override fun getHandlerId(): String = "order-created"

override fun getHandlerAliases(): Set<String> =
    setOf("com.example.LegacyOrderHandler#handle(com.example.OrderCreatedRecord)")
```

</TabItem>
<TabItem value="java" label="Java">

```java
@OutboxHandler(
    id = "order-created",
    aliases = { "com.example.LegacyOrderHandler#handle(com.example.OrderCreatedRecord)" }
)
public void handleOrderCreated(OrderCreatedRecord payload) {
    // ...
}
```

For interface-based handlers, override `getHandlerAliases()`:

```java
@Override
public String getHandlerId() {
    return "order-created";
}

@Override
public Set<String> getHandlerAliases() {
    return Set.of("com.example.LegacyOrderHandler#handle(com.example.OrderCreatedRecord)");
}
```

</TabItem>
</Tabs>

:::warning ID and Alias Requirements

- IDs and aliases must not be blank.
- Every canonical ID and alias must be globally unique across all handlers.
- Repeating the canonical ID or an alias within the same handler is deduplicated.
- A collision between two handlers causes application startup to fail rather than routing records
  ambiguously.
- Aliases are lookup-only; only the canonical handler ID is persisted for new records.

:::

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
@OutboxHandler
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
@OutboxHandler
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
    @OutboxHandler
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
    @OutboxHandler
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
    val handlerId: String             // Handler that failed
    val key: String                   // Record key
    val createdAt: Instant            // When record was created
    val failureCount: Int             // Number of failed attempts
    val lastException: Throwable?     // Last exception thrown
    val context: Map<String, String>  // Propagated context (traceId, tenantId, etc.)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public interface OutboxFailureContext {
    String getHandlerId();           // Handler that failed
    String getKey();                 // Record key
    Instant getCreatedAt();          // When record was created
    int getFailureCount();           // Number of failed attempts
    Throwable getLastException();    // Last exception thrown
    Map<String, String> getContext(); // Propagated context (traceId, tenantId, etc.)
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
    @OutboxHandler
    fun handleOrderCreated(payload: OrderEvent) {
        orderService.create(payload)
    }
    @OutboxHandler
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
    @OutboxHandler
    public void handleOrderCreated(OrderEvent payload) {
        orderService.create(payload);
    }
    @OutboxHandler
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
