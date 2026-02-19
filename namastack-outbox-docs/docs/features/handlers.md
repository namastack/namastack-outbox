# Handlers

## Handler Types & Interfaces

The library provides two complementary handler interfaces for different use cases:

### Typed Handlers (Type-Safe)

Process specific payload types with full type safety. Recommended for most cases as handlers are type-checked at compile time.

=== "Kotlin"

    ```kotlin
    @Component
    class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedRecord> {
        override fun handle(payload: OrderCreatedRecord) {
            println("Processing order: ${payload.orderId}")
            eventPublisher.publish(payload)
        }
    }
    ```

=== "Java"

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

### Generic Handlers (Multi-Type)

Process any payload type with pattern matching. Use for catch-all or multi-type routing logic.

=== "Kotlin"

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

=== "Java"

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

### Handler Invocation Order

When multiple handlers are registered:

1. **All matching typed handlers** are invoked first (in registration order)
2. **All generic handlers** are invoked second (catch-all)

---

## Annotation-based Handlers

Use `@OutboxHandler` annotation for method-level handler registration as an alternative to implementing interfaces:

=== "Kotlin"

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

=== "Java"

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

!!! note "Handler Signature Requirements"
    - **Typed handlers** can accept 1 or 2 parameters:
        - `fun handle(payload: T)` - Payload only
        - `fun handle(payload: T, metadata: OutboxRecordMetadata)` - Payload + metadata
    - **Generic handlers** must accept 2 parameters:
        - `fun handle(payload: Any, metadata: OutboxRecordMetadata)` - Required signature

**Interface vs Annotation:**

- **Interfaces**: Best when entire class is dedicated to handling a single type
- **Annotations**: Best when a class handles multiple types or mixing with other logic

---

## Fallback Handlers

!!! success "Graceful Degradation (Since 1.0.0)"
    Fallback handlers provide a safety net when all retries are exhausted, allowing for compensating actions, dead letter queue publishing, or alternative processing strategies.

Fallback handlers are automatically invoked when:

- **Retries Exhausted**: The record has exceeded the maximum retry count
- **Non-Retryable Exceptions**: An exception is thrown that should not be retried (based on retry policy)

### Interface-Based Fallback Handlers

Implement `OutboxFallbackHandler` interface for type-safe fallback handling:

=== "Kotlin"

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

=== "Java"

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

### Annotation-Based Fallback Handlers

Use `@OutboxFallbackHandler` annotation for method-level fallback registration:

=== "Kotlin"

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

=== "Java"

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

### OutboxFailureContext

The `OutboxFailureContext` provides comprehensive failure information:

```kotlin
interface OutboxFailureContext {
    val handlerId: String              // Handler that failed
    val key: String                    // Record key
    val createdAt: Instant            // When record was created
    val failureCount: Int             // Number of failed attempts
    val lastException: Throwable?     // Last exception thrown
    val context: Map<String, String>  // Propagated context (traceId, tenantId, etc.)
}
```

### Fallback Behavior

**Record Status After Fallback:**

- **Fallback Succeeds**: Record marked as `COMPLETED`
- **Fallback Fails**: Record marked as `FAILED` (requires manual intervention)

**Automatic Matching:**

Fallback handlers are automatically matched to primary handlers by payload type. One fallback handler can serve multiple primary handlers processing the same payload type.

=== "Kotlin"

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

=== "Java"

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

!!! warning "Fallback Handler Requirements"
    - Only **one fallback handler per payload type** is supported
    - Fallback handlers must match the payload type exactly
    - Fallback signature: `fun handle(payload: T, context: OutboxFailureContext)`

### Fallback Use Cases

**Common use cases for fallback handlers:**

1. **Dead Letter Queue**: Publish failed records to a DLQ for later analysis
2. **Alert & Monitoring**: Send alerts when records fail permanently
3. **Compensating Actions**: Execute compensating transactions (e.g., refund, rollback)
4. **Alternative Processing**: Route to alternative processing logic
5. **Audit Logging**: Log failure details for compliance and debugging
