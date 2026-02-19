[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/namastack/namastack-outbox/releases/tag/v1.0.0)
[![CodeFactor](https://www.codefactor.io/repository/github/namastack/namastack-outbox/badge)](https://www.codefactor.io/repository/github/namastack/namastack-outbox)
[![codecov](https://codecov.io/github/namastack/namastack-outbox/graph/badge.svg?token=TZS1OQB4XC)](https://codecov.io/github/namastack/namastack-outbox)
[![javadoc](https://javadoc.io/badge2/io.namastack/namastack-outbox-core/javadoc.svg)](https://javadoc.io/doc/io.namastack/namastack-outbox-core)
[![namastack-outbox CI](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml/badge.svg)](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml)
[![GitHub Release Date](https://img.shields.io/github/release-date/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/releases/latest)
[![GitHub last commit](https://img.shields.io/github/last-commit/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/commits/main)
[![dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Namastack Outbox for Spring Boot

A robust Spring Boot library for **Java and Kotlin** projects that implements the 
**Transactional Outbox Pattern** for reliable record publishing in distributed systems. Ensures 
records are never lost through atomic persistence and automatic retry logic
with handler-based processing and partition-aware horizontal scaling.

## Key Features

- ✅ **Transactional Atomicity**: Records saved in same transaction as domain data
- ✅ **Zero Message Loss**: Database-backed with at-least-once delivery
- ✅ **Horizontal Scaling**: Automatic partition assignment across instances
- ✅ **Automatic Retry**: Exponential backoff, fixed delay, linear with optional jitter
- ✅ **Handler-Based**: Annotation-based or interface-based handler registration
- ✅ **Type-Safe Handlers**: Generic or typed handler support
- ✅ **Fallback Handlers**: Graceful degradation when retries are exhausted
- ✅ **Flexible Payloads**: Store any type - events, commands, notifications, etc.
- ✅ **Context Propagation**: Trace IDs, tenant info, correlation IDs across async boundaries
- ✅ **Ordered Processing**: Records with same key processed sequentially
- ✅ **Built-in Metrics**: Micrometer integration for monitoring

---

## 📖 Documentation

For detailed information about features, configuration, and advanced topics, visit the **[complete documentation](https://outbox.namastack.io)**.

Quick links:
- [API Reference (Javadoc)](https://javadoc.io/doc/io.namastack/namastack-outbox-api)
- [GitHub Issues](https://github.com/namastack/namastack-outbox/issues)
- [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions)

---

## 🚀 Quick Start (5 Minutes)

### 1. Add Dependency

**Gradle (Kotlin DSL):**

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jdbc:1.0.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>io.namastack</groupId>
    <artifactId>namastack-outbox-starter-jdbc</artifactId>
    <version>1.0.0</version>
</dependency>
```

> **Note:** We use the JDBC starter here for automatic schema creation. For JPA/Hibernate projects, see [JPA Setup](#jpa-setup) below.

### 2. Enable Scheduling

```kotlin
@SpringBootApplication
@EnableScheduling  // Required for automatic outbox processing
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
```

### 3. Create Handlers

```kotlin
@Component
class OrderHandlers {
    // Typed handler - processes specific payload type
    @OutboxHandler
    fun handleOrder(payload: OrderCreatedRecord) {
        eventPublisher.publish(payload)
    }
    
    // Generic handler - processes any payload type
    @OutboxHandler
    fun handleAny(payload: Any, metadata: OutboxRecordMetadata) {
        when (payload) {
            is OrderCreatedRecord -> eventPublisher.publish(payload)
            is PaymentProcessedEvent -> paymentService.process(payload)
            else -> logger.warn("Unknown payload type")
        }
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
        
        // Schedule event - saved atomically with the order
        outbox.schedule(
            payload = OrderCreatedEvent(order.id, order.customerId),
            key = "order-${order.id}"  // Groups records for ordered processing
        )
    }
}
```

**Alternative: Using Spring's ApplicationEventPublisher**

If you prefer Spring's native event publishing, annotate your events with `@OutboxEvent`:

```kotlin
@OutboxEvent(key = "#this.orderId")  // SpEL: uses 'orderId' field
data class OrderCreatedEvent(
    val orderId: String,
    val customerId: String,
    val region: String,
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
            OrderCreatedEvent(order.id, order.customerId, order.region, order.amount)
        )
    }
}
```

Both approaches work equally well. Choose based on your preference:
- **Explicit `outbox.schedule()`**: More control, clearer intent, supports any payload type
- **`@OutboxEvent` + `ApplicationEventPublisher`**: More Spring idiomatic for domain events

### 5. Configure (Optional)

```yaml
namastack:
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

For a complete list of all configuration options, see [Configuration Reference](https://outbox.namastack.io/latest/features/configuration/).

**That's it!** Your records are now reliably persisted and processed.

---

## JPA Setup

If you prefer using JPA/Hibernate instead of JDBC, use the JPA starter:

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jpa:1.0.0")
}
```

**Schema Management Options:**

The JPA module does **not** support automatic schema creation. Choose one of these options:

**Option 1: Hibernate DDL Auto (Development only)**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create  # or create-drop
```

**Option 2: Flyway/Liquibase (Recommended for Production)**

Use the SQL schema files from our repository:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

See [example-flyway-jpa](namastack-outbox-examples/namastack-outbox-example-flyway-jpa) for a complete example.

---

## Features

### Handlers

Process outbox records using annotation-based handlers:

```kotlin
@Component
class MyHandlers {
    // Typed handler - processes specific payload type
    @OutboxHandler
    fun handleOrder(payload: OrderCreatedEvent) {
        println("Processing order: ${payload.orderId}")
    }

    // Generic handler - processes any payload type
    @OutboxHandler
    fun handleAny(payload: Any, metadata: OutboxRecordMetadata) {
        when (payload) {
            is OrderCreatedEvent -> handleOrder(payload)
            is PaymentProcessedEvent -> handlePayment(payload)
            else -> logger.warn("Unknown payload: ${payload::class.simpleName}")
        }
    }
}
```

**Handler Invocation Order:**
1. All matching typed handlers (in registration order)
2. All generic handlers (catch-all)

For interface-based handlers, see [Handler Documentation](https://outbox.namastack.io/latest/features/handlers/).

---

### Fallback Handlers

Gracefully handle permanently failed records after all retries are exhausted.

```kotlin
@Component
class OrderHandlers {
    @OutboxHandler
    fun handleOrder(payload: OrderEvent) {
        emailService.send(payload.email)  // May fail
    }

    @OutboxFallbackHandler
    fun handleOrderFailure(payload: OrderEvent, context: OutboxFailureContext) {
        // Invoked when handleOrder fails permanently
        logger.error("Order ${payload.orderId} failed after ${context.failureCount} attempts")
        deadLetterQueue.publish(payload)
    }
}
```

**Features:**
- Automatic invocation when retries exhausted or non-retryable exceptions occur
- Automatically matched by payload type
- Access to failure details and metadata via `OutboxFailureContext`
- Record marked COMPLETED if fallback succeeds

For interface-based fallback handlers, see [Fallback Documentation](https://outbox.namastack.io/latest/features/handlers/#fallback-handlers).

---

### Context Propagation

Preserve context (trace IDs, tenant info, correlation IDs) across async boundaries.

```kotlin
@Component
class TracingContextProvider(
    private val tracer: Tracer
) : OutboxContextProvider {
    override fun provide(): Map<String, String> {
        val currentSpan = tracer.currentSpan() ?: return emptyMap()
        return mapOf(
            "traceId" to currentSpan.context().traceId(),
            "spanId" to currentSpan.context().spanId()
        )
    }
}
```

**Accessing Context in Handlers:**

```kotlin
@Component
class OrderHandler {
    @OutboxHandler
    fun handle(payload: OrderEvent, metadata: OutboxRecordMetadata) {
        // Access context via metadata.context
        val traceId = metadata.context["traceId"]
        val tenantId = metadata.context["tenantId"]
        
        logger.info("Processing order ${payload.orderId} [trace: $traceId]")
    }
    
    @OutboxFallbackHandler
    fun handleFailure(payload: OrderEvent, failureContext: OutboxFailureContext) {
        // Access context via failureContext.context
        val traceId = failureContext.context["traceId"]
        
        deadLetterQueue.publish(payload, mapOf("traceId" to traceId))
    }
}
```

**Features:**
- Automatic context capture during `outbox.schedule()`
- Context available via `metadata.context` in handlers
- Context available via `failureContext.context` in fallback handlers
- Multiple providers supported (merged automatically)
- Supports distributed tracing, multi-tenancy, correlation IDs

For complete documentation and examples, see [Context Propagation Documentation](https://outbox.namastack.io/latest/features/context-propagation/).

---

### Retry Policies

Configure retry behavior in `application.yml` to set the **default policy for all handlers**.

**Exponential Backoff (Recommended):**

```yaml
namastack:
  outbox:
    retry:
      policy: exponential
      max-retries: 3
      exponential:
        initial-delay: 1000      # 1 second
        max-delay: 60000         # 1 minute
        multiplier: 2.0
      # Optional: Control which exceptions trigger retries
      include-exceptions:
        - java.net.SocketTimeoutException
        - org.springframework.web.client.ResourceAccessException
      exclude-exceptions:
        - java.lang.IllegalArgumentException
        - javax.validation.ValidationException
```

Delays: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped)

**Fixed Delay:**

```yaml
namastack:
  outbox:
    retry:
      policy: fixed
      max-retries: 5
      fixed:
        delay: 5000              # Always 5 seconds
```

**Linear Backoff:**

```yaml
namastack:
  outbox:
    retry:
      policy: linear
      max-retries: 5
      linear:
        initial-delay: 2000      # Start with 2 seconds
        increment: 2000          # Add 2 seconds each retry
        max-delay: 60000         # Cap at 1 minute
```

Delays: 2s → 4s → 6s → 8s → 10s

**Jittered (Prevents Thundering Herd):**

```yaml
namastack:
  outbox:
    retry:
      policy: exponential
      max-retries: 4
      exponential:
        initial-delay: 2000
        max-delay: 60000
      multiplier: 2.0
      jitter: 1000               # Add [-1000ms, 1000ms] random delay
```

**Custom Retry Policies:**

**Global Custom Policy** - Override default for all handlers using bean named `outboxRetryPolicy`:

```kotlin
@Configuration
class OutboxConfig {
    @Bean("outboxRetryPolicy")
    fun customRetryPolicy(): OutboxRetryPolicy {
        return object : OutboxRetryPolicy {
            override fun shouldRetry(exception: Throwable): Boolean {
                return when (exception) {
                    is IllegalArgumentException -> false
                    is PaymentDeclinedException -> false
                    else -> true
                }
            }

            override fun nextDelay(failureCount: Int) = Duration.ofSeconds(5)
            override fun maxRetries() = 3
        }
    }
}
```

**Per-Handler Policy** - Override via `@OutboxRetryable`:

```kotlin
@Component  
class PaymentHandler {
    @OutboxHandler
    @OutboxRetryable(AggressiveRetryPolicy::class)
    fun handlePayment(payload: PaymentEvent) {
        paymentGateway.process(payload)
    }
}

@Component
class AggressiveRetryPolicy : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable) = true
    override fun nextDelay(failureCount: Int) = Duration.ofMillis(500)
    override fun maxRetries() = 10
}
```

**OutboxRetryPolicy.Builder API** - Cleaner way to create custom policies:

```kotlin
@Configuration
class OutboxConfig {
    fun customRetryPolicy(): OutboxRetryPolicy {
        return OutboxRetryPolicy.builder()
            .maxRetries(5)
            .exponentialBackoff(
                initialDelay = Duration.ofSeconds(10),
                multiplier = 2.0,
                maxDelay = Duration.ofMinutes(5)
            )
            .jitter(Duration.ofSeconds(2))
            .retryOn(TimeoutException::class.java, IOException::class.java)
            .noRetryOn(IllegalArgumentException::class.java, PaymentDeclinedException::class.java)
            .build()
    }
}
```

> **Tip:** A `OutboxRetryPolicy.Builder` bean named `outboxRetryPolicyBuilder` is automatically configured based on your `application.yml` settings. You can inject it to retain property-driven defaults and add programmatic customizations.

---

## How It Works

### The Pattern

The Transactional Outbox Pattern ensures reliable message delivery by persisting outbox records in the same database transaction as your business data. This guarantees atomicity - either both succeed or both fail together.

**Key Guarantees:**

- **Atomicity**: Outbox record is saved in the same transaction as your domain data. No separate transaction means no risk of inconsistency.
- **At-Least-Once Delivery**: Records are processed at least once, but may be processed multiple times if failures occur. Make your handlers idempotent.
- **Ordering**: Records with the same `key` are processed sequentially by the same instance. This ensures correct ordering for related records.
- **Automatic Retry**: Failed records are automatically retried based on the configured retry policy. No manual intervention needed for transient failures.
- **Graceful Degradation**: Fallback handlers provide a safety net when all retries are exhausted, allowing for compensating actions or dead letter queue publishing.

### Partitioning & Scaling

The library uses consistent hashing to distribute records across multiple instances, enabling horizontal scaling while maintaining ordering guarantees.

**Partition Assignment:**

Each instance is assigned a subset of 256 partitions. Records are assigned to partitions based on a hash of their `key`:

```
Instance 1 → partition 0-84   → handles "order-123", "order-456"
Instance 2 → partition 85-169 → handles "payment-789", "customer-001"
Instance 3 → partition 170-255 → handles other keys
```

**Key-Based Ordering:**

Records with the same `key` always hash to the same partition, ensuring they're processed by the same instance in order:

```
Key "order-123" → partition 42 → Instance 1
Key "order-123" → partition 42 → Instance 1  (same partition, same order)
Key "order-456" → partition 78 → Instance 1
Key "payment-789" → partition 142 → Instance 2
```

**Automatic Rebalancing:**

When an instance fails or a new instance joins, partitions are automatically reassigned:

```
Before (3 instances):
Instance 1 → partitions 0-84
Instance 2 → partitions 85-169
Instance 3 → partitions 170-255

Instance 2 fails:
Instance 1 → partitions 0-127      (takes over half of Instance 2's partitions)
Instance 3 → partitions 128-255    (takes over other half)

New Instance 4 joins:
Instance 1 → partitions 0-63
Instance 3 → partitions 64-127
Instance 4 → partitions 128-191
Instance 5 → partitions 192-255
(Partitions redistributed evenly)
```

**Stale Instance Detection:**

Each instance sends periodic heartbeats. If an instance stops sending heartbeats (crash, network partition), it's marked as stale and its partitions are reassigned to healthy instances. This ensures no records are left unprocessed.

**Processing Isolation:**

Each instance only processes records from its assigned partitions. This prevents duplicate processing and ensures clean separation of work across the cluster.

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

Any JPA-compatible database is supported. Automatic schema creation is available in the JDBC module for:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server

**Schema Files for Flyway/Liquibase:**

If you manage your database schema manually with Flyway or Liquibase, you can find the SQL schema files for all supported databases here:

👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

---

## What's New in 1.0.0

### ⚠️ Breaking Changes

This release contains breaking changes. See [Migration Guide](#migrating-to-100) for details.

### 🏷️ Configuration Property Prefix (GH-176)

All configuration properties now use the `namastack.outbox` prefix for better namespacing:

```yaml
namastack:
  outbox:
    poll-interval: 2000
    retry:
      policy: exponential
```

### 🔧 JDBC Schema Initialization Enabled by Default (GH-177)

The JDBC module now automatically creates outbox tables on startup. No configuration needed for basic usage:

```gradle
implementation("io.namastack:namastack-outbox-starter-jdbc:1.0.0")
// Tables are created automatically!
```

### 🗑️ Deprecated `jittered` Policy Removed (GH-181)

The deprecated `policy: "jittered"` has been removed. Use the `jitter` property with any base policy:

```yaml
namastack:
  outbox:
    retry:
      policy: exponential
      jitter: 500  # Works with any policy
```

---

## Migration Guide

### Migrating to 1.0.0

Version 1.0.0 introduces several **breaking changes** that require updates to your configuration.

#### 1. Configuration Property Prefix Change

All configuration properties now use the `namastack.outbox` prefix instead of `outbox`.

**Before (1.0.0):**
```yaml
outbox:
  poll-interval: 2000
  batch-size: 10
  retry:
    policy: exponential
    max-retries: 3
  jdbc:
    schema-initialization:
      enabled: true
```

**After (1.0.0):**
```yaml
namastack:
  outbox:
    poll-interval: 2000
    batch-size: 10
    retry:
      policy: exponential
      max-retries: 3
    jdbc:
      schema-initialization:
        enabled: true
```

**Properties file format:**
```properties
# Before
outbox.poll-interval=2000
outbox.enabled=false

# After
namastack.outbox.poll-interval=2000
namastack.outbox.enabled=false
```

#### 2. JDBC Schema Initialization Now Enabled by Default

The JDBC module now **automatically creates outbox tables on startup by default**.

**What changed:**
- `namastack.outbox.jdbc.schema-initialization.enabled` now defaults to `true` (was `false`)
- No configuration needed for basic usage with JDBC module

**Action required if using Flyway/Liquibase:**
If you manage your schema with Flyway or Liquibase, no action is needed - the schema initialization has no effect when tables already exist.

**Action required if using custom table prefix/schema name:**
You must explicitly disable schema initialization:
```yaml
namastack:
  outbox:
    jdbc:
      table-prefix: "myapp_"
      schema-name: "custom_schema"
      schema-initialization:
        enabled: false  # Must disable when using custom naming
```

#### 3. Deprecated `jittered` Retry Policy Removed

The `policy: "jittered"` configuration has been removed. Use the `jitter` property with any base policy instead.

**Before (deprecated):**
```yaml
outbox:
  retry:
    policy: jittered
    jittered:
      base-policy: exponential
      jitter: 500
```

**After (1.0.0):**
```yaml
namastack:
  outbox:
    retry:
      policy: exponential  # or fixed, linear
      exponential:
        initial-delay: 2000
        max-delay: 60000
        multiplier: 2.0
      jitter: 500  # Add jitter to any policy
```

#### Migration Checklist

- [ ] Update all `outbox.*` properties to `namastack.outbox.*`
- [ ] Update YAML configuration to nest `outbox:` under `namastack:`
- [ ] If using `policy: jittered`, migrate to `jitter` property with base policy
- [ ] If using custom table prefix/schema name with JDBC, explicitly set `schema-initialization.enabled: false`
- [ ] Test your application to ensure configuration is correctly applied

---

## Version Stability & Semantic Versioning

From **1.0.0 onwards**, Namastack Outbox follows strict **semantic versioning**:

- **Major versions (x.0.0)**: Breaking changes allowed
  - API changes, schema changes, behavior changes
  - Migration guide provided for each major version
  
- **Minor versions (1.x.0)**: New features, backward compatible
  - New APIs, new features, enhancements
  - No breaking changes, safe to upgrade
  
- **Patch versions (1.0.x)**: Bug fixes only
  - Bug fixes, security patches
  - No new features, no breaking changes

**1.0.0 Status:**
- This is the **stable 1.0.0 General Availability release**
- API is locked and stable (no breaking changes until 2.0.0)
- Production-ready with comprehensive testing
- Full semantic versioning from this point forward

**What This Means for You:**
- ✅ Safe to use in production
- ✅ Upgrade within same major version without fear of breaking changes
- ✅ Clear migration path when major versions are released
- ✅ Predictable release cycle and stability guarantees

---

## Requirements

- Java 17+
- Spring Boot 4.0.0+
- Kotlin 2.2+ (optional, Java is fully supported)

---

## Support

- 📖 [Documentation](https://outbox.namastack.io)
- 🐛 [Issues](https://github.com/namastack/namastack-outbox/issues)
- 💬 [Discussions](https://github.com/namastack/namastack-outbox/discussions)
- 📦 [Example Projects](namastack-outbox-examples)

---

## License

Apache License 2.0 - See [LICENSE](./LICENSE)

---

## Acknowledgments

- Built with ❤️ by [Namastack](https://namastack.io)
- Inspired by the [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- Powered by Spring Boot & Kotlin
