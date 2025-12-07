[![CodeFactor](https://www.codefactor.io/repository/github/namastack/namastack-outbox/badge)](https://www.codefactor.io/repository/github/namastack/namastack-outbox)
[![codecov](https://codecov.io/github/namastack/namastack-outbox/graph/badge.svg?token=TZS1OQB4XC)](https://codecov.io/github/namastack/namastack-outbox)
[![javadoc](https://javadoc.io/badge2/io.namastack/namastack-outbox-core/javadoc.svg)](https://javadoc.io/doc/io.namastack/namastack-outbox-core)
[![namastack-outbox CI](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml/badge.svg)](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml)
[![GitHub Release Date](https://img.shields.io/github/release-date/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/releases/latest)
[![GitHub last commit](https://img.shields.io/github/last-commit/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/commits/main)
[![dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Namastack Outbox for Spring Boot

A robust Spring Boot library for **Java and Kotlin** projects that implements the **Transactional Outbox Pattern** for reliable record publishing
in distributed systems. Ensures records are never lost through atomic persistence and automatic retry logic
with handler-based processing and partition-aware horizontal scaling.

## Key Features

- ✅ **Transactional Atomicity**: Records saved in same transaction as domain data
- ✅ **Automatic Retry**: Exponential backoff, fixed delay, or jittered policies
- ✅ **Ordered Processing**: Records with same key processed sequentially
- ✅ **Handler-Based**: Annotation-based or interface-based handler registration
- ✅ **Horizontal Scaling**: Automatic partition assignment across instances
- ✅ **Zero Message Loss**: Database-backed with at-least-once delivery
- ✅ **Type-Safe Handlers**: Generic or typed handler support
- ✅ **Built-in Metrics**: Micrometer integration for monitoring
- ✅ **Flexible Payloads**: Store any type - events, commands, notifications, etc.

---

## 📖 Documentation

For detailed information about features, configuration, and advanced topics, visit the **[complete documentation](https://outbox.namastack.io)**.

Quick links:
- [API Reference (Javadoc)](https://javadoc.io/doc/io.namastack/namastack-outbox-core)
- [GitHub Issues](https://github.com/namastack/namastack-outbox/issues)
- [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions)

---

## 🚀 Quick Start (5 Minutes)

### 1. Add Dependency

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jpa:0.4.0")
}
```

### 2. Enable Outbox

```kotlin
@SpringBootApplication
@EnableOutbox
@EnableScheduling
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
```

### 3. Create a Handler

```kotlin
@Component
class OrderRecordHandler : OutboxTypedHandler<OrderCreatedRecord> {
    override fun handle(payload: OrderCreatedRecord) {
        // Process the record - this will be called automatically when ready
        eventPublisher.publish(payload)
    }
}
```

### 4. Schedule Records Atomically

```kotlin
@Service
class OrderService(
    private val outbox: Outbox,
    private val orderRepository: OrderRepository
) {
    @Transactional
    fun createOrder(command: CreateOrderCommand) {
        val order = Order.create(command)
        orderRepository.save(order)
        
        // Schedule record - saved atomically with the order
        outbox.schedule(
            payload = OrderCreatedRecord(order.id, order.customerId),
            key = "order-${order.id}"  // Groups records for ordered processing
        )
    }
}
```

**Alternative: Using Spring's ApplicationEventPublisher**

If you prefer Spring's native event publishing, annotate your events with `@OutboxEvent`:

```kotlin
@OutboxEvent(key = "#event.orderId")  // SpEL expression for key resolution
data class OrderCreatedEvent(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal
)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    @Transactional
    fun createOrder(command: CreateOrderCommand) {
        val order = Order.create(command)
        orderRepository.save(order)
        
        // Publish event - automatically saved to outbox atomically
        eventPublisher.publishEvent(
            OrderCreatedEvent(order.id, order.customerId, order.amount)
        )
    }
}
```

Both approaches work equally well. Choose based on your preference:
- **Explicit `outbox.schedule()`**: More control, clearer intent, supports any payload type
- **`@OutboxEvent` + `ApplicationEventPublisher`**: More Spring idiomatic for domain events

### 5. Configure (Optional)

```yaml
outbox:
  poll-interval: 2000
  batch-size: 10
  retry:
    policy: "exponential"
    max-retries: 3
    exponential:
      initial-delay: 1000
      max-delay: 60000
      multiplier: 2.0
```

For a complete list of all configuration options, see [Configuration Reference](#configuration-reference).

**That's it!** Your records are now reliably persisted and processed.

---

## Handler Types

### Typed Handlers (Type-Safe)

Process specific payload types with full type safety:

```kotlin
@Component
class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedPayload> {
    override fun handle(payload: OrderCreatedPayload) {
        println("Processing order: ${payload.orderId}")
    }
}
```

Or use annotations:

```kotlin
@Component
class MyHandlers {
    @OutboxHandler
    fun handleOrderCreated(payload: OrderCreatedPayload) {
        // ...
    }

    @OutboxHandler
    fun handlePaymentProcessed(payload: PaymentProcessedPayload) {
        // ...
    }
}
```

### Generic Handlers (Multi-Type)

Process any payload type with pattern matching:

```kotlin
@Component
class UniversalHandler : OutboxHandler {
    override fun handle(payload: Any, metadata: OutboxRecordMetadata) {
        when (payload) {
            is OrderCreatedPayload -> handleOrder(payload)
            is PaymentProcessedPayload -> handlePayment(payload)
            is CreateCustomerCommand -> createCustomer(payload)
            else -> logger.warn("Unknown payload: ${payload::class.simpleName}")
        }
    }
}
```

**Handler Invocation Order:**
1. All matching typed handlers (in registration order)
2. All generic handlers (catch-all)

---

## Retry Policies

### Exponential Backoff (Recommended)

```yaml
outbox:
  retry:
    policy: exponential
    max-retries: 3
    exponential:
      initial-delay: 1000      # 1 second
      max-delay: 60000         # 1 minute
      multiplier: 2.0
```

Delays: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped)

### Fixed Delay

```yaml
outbox:
  retry:
    policy: fixed
    max-retries: 5
    fixed:
      delay: 5000              # Always 5 seconds
```

### Jittered (Prevents Thundering Herd)

```yaml
outbox:
  retry:
    policy: jittered
    max-retries: 4
    jittered:
      base-policy: exponential
      jitter: 1000             # ±0-1000ms random
```

---

## Configuration Reference

```yaml
outbox:
  # Polling
  poll-interval: 2000                    # How often to check for pending records
  batch-size: 10                         # Records per polling cycle

  # Processing
  processing:
    stop-on-first-failure: true          # Stop sequence if record fails
    delete-completed-records: false      # Cleanup completed records
    executor-core-pool-size: 4
    executor-max-pool-size: 8

  # Event Multicaster (for @OutboxEvent support)
  multicaster:
    enabled: true                        # Enable automatic outbox persistence for @OutboxEvent

  # Instance Coordination (for clustering)
  instance:
    graceful-shutdown-timeout-seconds: 15
    stale-instance-timeout-seconds: 30
    heartbeat-interval-seconds: 5

  # Retry Strategy
  retry:
    max-retries: 3
    policy: exponential                  # fixed | exponential | jittered
    exponential:
      initial-delay: 1000
      max-delay: 60000
      multiplier: 2.0
    fixed:
      delay: 5000
    jittered:
      base-policy: exponential
      jitter: 500

  # Database Schema
  schema-initialization:
    enabled: true                        # Auto-create tables on startup
```

---

## How It Works

### The Pattern

```
1. Application saves Order + schedules OrderCreatedRecord
   ↓ (atomic transaction)
2. Record persisted to outbox_record table
   ↓
3. Background scheduler polls for unprocessed records
   ↓
4. Scheduler invokes registered handlers (typed → generic)
   ↓
5. Handler processes record successfully → marked COMPLETED
   OR
   Handler throws exception → scheduled for retry with backoff
   ↓
6. On max retries exceeded → marked FAILED (manual intervention needed)
```

### Partitioning & Scaling

Records with the same `key` are processed by the same instance in order:

```
Instance 1 → partition 0-84   → handles "order-123", "order-456"
Instance 2 → partition 85-169 → handles "payment-789", "customer-001"
Instance 3 → partition 170-255 → handles other keys

If Instance 2 fails:
Instance 1 → partition 0-127
Instance 3 → partition 128-255
(Automatic rebalancing)
```

---

## Monitoring

### Metrics (via Micrometer)

```yaml
management:
  endpoints:
    web:
      exposure: include=metrics,health
```

Available metrics:

```
outbox.records.count{status="new|failed|completed"}
outbox.partitions.assigned.count
outbox.partitions.pending.records.total
outbox.partitions.pending.records.max
```

---

## Supported Databases

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server

---

## Breaking Changes in 0.4.0

### Handler API Changes

**Before (0.3.0):**
```kotlin
class MyProcessor : OutboxRecordProcessor {
    override fun process(record: OutboxRecord) { }
}
```

**After (0.4.0):**
```kotlin
// Type-safe handlers
class OrderHandler : OutboxTypedHandler<OrderCreatedPayload> {
    override fun handle(payload: OrderCreatedPayload) { }
}

// Or generic handlers
class MyHandler : OutboxHandler {
    override fun handle(payload: Any, metadata: OutboxRecordMetadata) { }
}
```

### Key Benefits of 0.4.0

- ✨ **Type-Safe**: Handler signatures are type-checked at compile time
- ✨ **Simpler**: No manual payload type detection needed
- ✨ **More Flexible**: Both typed and generic handlers work together
- ✨ **Better Testing**: Handlers are regular Spring beans, easy to mock
- ✨ **Flexible Payloads**: Store any type - events, commands, notifications, etc.

### Migration

The migration is straightforward:

1. Replace `OutboxRecordProcessor` with `OutboxTypedHandler<YourPayloadType>`
2. Update handler method signature from `process(record: OutboxRecord)` to `handle(payload: T)`
3. Change `outbox.schedule()` calls to pass the payload object directly (not serialized)

**Before:**
```kotlin
outbox.schedule(
    eventType = "OrderCreated",
    payload = objectMapper.writeValueAsString(event)
)
```

**After:**
```kotlin
outbox.schedule(
    payload = orderCreatedPayload,  // Type-safe! Can be event, command, etc.
    key = "order-${orderCreatedPayload.orderId}"
)
```

### Database Schema Changes

Version 0.4.0 introduces breaking schema changes. **Drop and recreate the outbox tables:**

```sql
-- Drop old tables (data will be lost - process pending records first!)
DROP TABLE IF EXISTS outbox_record CASCADE;
DROP TABLE IF EXISTS outbox_instance CASCADE;
DROP TABLE IF EXISTS outbox_partition CASCADE;

-- On next startup, Namastack Outbox will auto-create the new schema
-- (if outbox.schema-initialization.enabled=true)
```

**Migration Steps:**

1. **Before upgrading** (in version 0.3.0):
   - Process all pending records or accept loss of unprocessed records
   - Backup old outbox tables if needed for audit trail

2. **Upgrade** to 0.4.0:
   - Update dependency in `build.gradle.kts`
   - Drop old outbox tables (see SQL above)
   - Restart application
   - New schema is auto-created on startup

3. **After upgrade** (in version 0.4.0):
   - Register new handlers (TypedHandler or OutboxHandler)
   - Update record scheduling code
   - No data migration needed (fresh start)

**Automated Migration (Optional):**

If you want automatic table recreation, ensure:
```yaml
outbox:
  schema-initialization:
    enabled: true  # Default - creates schema on startup
```

---

## Common Patterns

### Idempotent Handler (Recommended)

```kotlin
@Component
class OrderHandler : OutboxTypedHandler<OrderCreatedPayload> {
    override fun handle(payload: OrderCreatedPayload) {
        // Check if already processed (idempotency key)
        if (processedRecordService.isProcessed(payload.id)) {
            return
        }
        
        // Process the payload
        eventPublisher.publish(payload)
        
        // Mark as processed
        processedRecordService.mark(payload.id)
    }
}
```

### Error Handling & Retries

**Automatic Retry on Exception:**

```kotlin
@Component
class PaymentHandler : OutboxTypedHandler<PaymentProcessedPayload> {
    override fun handle(payload: PaymentProcessedPayload) {
        try {
            paymentGateway.confirmPayment(payload.transactionId)
        } catch (e: TemporaryNetworkException) {
            // Throwing exception → Record scheduled for retry
            throw e
        } catch (e: PermanentPaymentFailureException) {
            // Not throwing exception → Handler completes successfully, no retry
            logger.error("Permanent failure for transaction ${payload.transactionId}", e)
        }
    }
}
```

**Custom Retry Policy (Fail-Fast on Specific Exceptions):**

```kotlin
// 1. Define the custom retry policy
class CustomRetryPolicy : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean {
        return when (exception) {
            is InvalidCredentialsException -> false      // Don't retry auth errors
            is PermanentFailureException -> false        // Don't retry permanent failures
            is TemporaryNetworkException -> true         // Retry network errors
            else -> true                                 // Default: retry
        }
    }

    override fun nextDelay(exception: Throwable): Duration {
        return when (exception) {
            is TemporaryNetworkException -> Duration.ofSeconds(5)
            else -> Duration.ofSeconds(1)
        }
    }
}

// 2. Register the bean via configuration
@Configuration
class OutboxConfiguration {
    @Bean
    fun customRetryPolicy(): OutboxRetryPolicy = CustomRetryPolicy()
}
```

The custom `OutboxRetryPolicy` bean is automatically detected and used by the framework.

---

## Requirements

- Java 21+
- Spring Boot 4.0.0+
- Kotlin 2.2+ (optional, Java is supported)

---

## Support

- 📖 [Documentation](https://outbox.namastack.io)
- 🐛 [Issues](https://github.com/namastack/namastack-outbox/issues)
- 💬 [Discussions](https://github.com/namastack/namastack-outbox/discussions)

---

## License

Apache License 2.0 - See [LICENSE](./LICENSE)

---

## Acknowledgments

- Built with ❤️ by [Namastack](https://namastack.io)
- Inspired by the Transactional Outbox Pattern
- Powered by Spring Boot & Kotlin

