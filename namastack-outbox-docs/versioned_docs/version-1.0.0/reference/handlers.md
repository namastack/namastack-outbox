---
title: Handlers
description: Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.
sidebar_position: 4
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
    override fun handle(payload: OrderCreatedRecord) {
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
    public void handle(OrderCreatedRecord payload) {
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

## Fallback Handlers

Fallback handlers provide a safety net when all retries are exhausted, allowing for compensating actions, dead letter queue publishing, or alternative processing strategies.

Fallback handlers are automatically invoked when:

- **Retries Exhausted**: The record has exceeded the maximum retry count
- **Non-Retryable Exceptions**: An exception is thrown that should not be retried (based on retry policy)

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
