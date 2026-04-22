[![Version](https://img.shields.io/badge/version-1.4.0-blue)](https://github.com/namastack/namastack-outbox/releases/tag/v1.4.0)
[![CodeFactor](https://www.codefactor.io/repository/github/namastack/namastack-outbox/badge)](https://www.codefactor.io/repository/github/namastack/namastack-outbox)
[![codecov](https://codecov.io/github/namastack/namastack-outbox/graph/badge.svg?token=TZS1OQB4XC)](https://codecov.io/github/namastack/namastack-outbox)
[![javadoc](https://javadoc.io/badge2/io.namastack/namastack-outbox-core/javadoc.svg)](https://javadoc.io/doc/io.namastack/namastack-outbox-core)
[![namastack-outbox CI](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml/badge.svg)](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml)
[![GitHub Release Date](https://img.shields.io/github/release-date/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/releases/latest)
[![GitHub last commit](https://img.shields.io/github/last-commit/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/commits/main)
[![dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Namastack Outbox for Spring Boot

Namastack Outbox is a modern, production-grade library for **Spring Boot** (Java & Kotlin) that
implements the **Transactional Outbox Pattern** for reliable, scalable, and observable event-driven
architectures. It guarantees that business events are never lost, are processed exactly once per
handler, and can be published to any system-whether via custom handlers, Kafka, RabbitMQ, SNS, or
other integrations.

## Key Features

- **Transactional Guarantees** - Outbox records are persisted atomically with your business data. No
  lost events, ever.
- **At-Least-Once Delivery** - Robust retry logic with exponential backoff, linear, fixed, and
  jittered strategies.
- **Ordered Processing** - Records with the same key are always processed sequentially, guaranteeing 
  strict ordering.
- **Horizontal Scaling** - Partitioned processing with automatic rebalancing across 256 partitions.
- **Flexible Handler Model** - Annotation-based (`@OutboxHandler`) or interface-based (
  `OutboxTypedHandler<T>`) handlers.
- **Fallback & Dead Letter** - Graceful degradation with `@OutboxFallbackHandler` when all retries
  are exhausted.
- **Context Propagation** - Trace IDs, tenant info, and correlation IDs flow automatically across
  async boundaries.
- **Adaptive Polling** - Dynamically adjusts polling interval based on workload for optimal DB
  efficiency.
- **Observability** - Built-in Micrometer metrics, Actuator endpoint, and distributed tracing.
- **Messaging Integrations** - Ready-to-use Kafka, RabbitMQ, and AWS SNS handlers with flexible
  routing.
- **Virtual Thread Support** - Automatic detection and use of virtual threads when available.
- **Auto-Configuration** - Sensible defaults, automatic `@EnableScheduling`, and deep Spring Boot
  integration.
- **Broad Database Support** - H2, MySQL, MariaDB, PostgreSQL, SQL Server, Oracle, and MongoDB.

---

## Documentation

For detailed information about features, configuration, and advanced topics, visit the **[complete documentation](https://www.namastack.io/outbox)**.

Quick links:

- [API Reference (Javadoc)](https://javadoc.io/doc/io.namastack/namastack-outbox-api)
- [GitHub Issues](https://github.com/namastack/namastack-outbox/issues)
- [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions)

---

## Quick Start

### 1. Add Dependency

**Gradle (Kotlin DSL):**

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jdbc:1.4.0")
}
```

**Maven:**

```xml

<dependency>
  <groupId>io.namastack</groupId>
  <artifactId>namastack-outbox-starter-jdbc</artifactId>
  <version>1.4.0</version>
</dependency>
```

> **Note:** The JDBC starter includes automatic schema creation. For JPA/Hibernate projects,
> see [JPA Setup](#jpa-setup) below. For MongoDB projects, see [MongoDB Setup](#mongodb-setup) below.

### 2. Create Handlers

<details open>
<summary><b>Kotlin</b></summary>

```kotlin
@Component
class OrderHandlers {
    @OutboxHandler
    fun handleOrder(payload: OrderCreatedEvent) {
        eventPublisher.publish(payload)
    }

    @OutboxHandler
    fun handleAny(payload: Any, metadata: OutboxRecordMetadata) {
        when (payload) {
            is OrderCreatedEvent -> eventPublisher.publish(payload)
            is PaymentProcessedEvent -> paymentService.process(payload)
        }
    }
}
```

</details>

<details>
<summary><b>Java</b></summary>

```java

@Component
public class OrderHandler implements OutboxTypedHandler<OrderCreatedEvent> {

  @Override
  public void handle(OrderCreatedEvent payload, OutboxRecordMetadata metadata) {
    eventPublisher.publish(payload);
  }
}
```

</details>

### 3. Schedule Records Atomically

<details open>
<summary><b>Kotlin</b></summary>

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

        outbox.schedule(
            payload = OrderCreatedEvent(order.id, order.customerId),
            key = "order-${order.id}"
        )
    }
}
```

</details>

<details>
<summary><b>Java</b></summary>

```java

@Service
public class OrderService {

  private final Outbox outbox;
  private final OrderRepository orderRepository;

  @Transactional
  public void createOrder(CreateOrderCommand command) {
    Order order = Order.create(command);
    orderRepository.save(order);

    outbox.schedule(
        new OrderCreatedEvent(order.getId(), order.getCustomerId()),
        "order-" + order.getId()
    );
  }
}
```

</details>

**Alternative: Using Spring's ApplicationEventPublisher**

Annotate your events with `@OutboxEvent` to automatically persist them to the outbox:

```kotlin
@OutboxEvent(key = "#this.orderId")
data class OrderCreatedEvent(val orderId: String, val customerId: String)

// Then simply use Spring's event publishing inside a @Transactional method
eventPublisher.publishEvent(OrderCreatedEvent(order.id, order.customerId))
```

### 4. Configure (Optional)

```yaml
namastack:
  outbox:
    polling:
      batch-size: 10
      trigger: fixed        # or "adaptive"
      fixed:
        interval: 2000
    retry:
      policy: exponential
      max-retries: 3
      exponential:
        initial-delay: 1000
        max-delay: 60000
        multiplier: 2.0
```

For a complete list of all configuration options,
see [Configuration Reference](https://www.namastack.io/outbox/reference/configuration/).

**That's it!** Your records are now reliably persisted and processed.

---

## JPA Setup

If you prefer using JPA/Hibernate instead of JDBC, use the JPA starter:

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jpa:1.4.0")
}
```

The JPA module does **not** support automatic schema creation. Use Flyway/Liquibase with
our [SQL schema files](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema),
or `ddl-auto: create` for development.

See [example-flyway-jpa](namastack-outbox-examples/namastack-outbox-example-flyway-jpa) for a
complete example.

---

## MongoDB Setup

For MongoDB projects, use the MongoDB starter:

```gradle
dependencies {
    implementation("io.namastack:namastack-outbox-starter-mongodb:1.5.0")
}
```

The MongoDB module automatically creates collections and indexes on startup via Spring Data MongoDB's
`auto-index-creation`. No manual schema management is required.

For production environments, you can manage indexes manually using the provided
[mongosh setup script](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-mongodb/src/main/resources/schema/mongodb-setup.js).
See the [MongoDB Schema documentation](https://www.namastack.io/outbox/reference/mongodb-schema/) for details.

### Custom Collection Prefix

Customize collection names for multi-tenant deployments or naming conventions:

```yaml
namastack:
  outbox:
    mongodb:
      collection-prefix: "myapp_"   # Results in: myapp_outbox_records, myapp_outbox_instances, etc.
```

See [example-mongodb](namastack-outbox-examples/namastack-outbox-example-mongodb) for a
complete example.

---

## Features at a Glance

### Handlers

Process outbox records using annotation-based or interface-based handlers. Typed handlers match
specific payload types; generic handlers catch all.

```kotlin
@OutboxHandler
fun handleOrder(payload: OrderCreatedEvent) { /* ... */
}

@OutboxFallbackHandler
fun handleFailure(payload: OrderCreatedEvent, context: OutboxFailureContext) {
    deadLetterQueue.publish(payload)
}
```

→ [Handler Documentation](https://www.namastack.io/outbox/reference/handlers/)

### Retry Policies

Configure retry behavior globally via properties (`exponential`, `fixed`, `linear`) or per-handler
via `@OutboxRetryable`. Use the `OutboxRetryPolicy.Builder` API for programmatic control.

```kotlin
val policy = OutboxRetryPolicy.builder()
    .maxRetries(5)
    .exponentialBackoff(Duration.ofSeconds(10), 2.0, Duration.ofMinutes(5))
    .jitter(Duration.ofSeconds(2))
    .retryOn(IOException::class.java)
    .noRetryOn(IllegalArgumentException::class.java)
    .build()
```

→ [Retry Documentation](https://www.namastack.io/outbox/reference/retry/)

### Context Propagation

Preserve context (trace IDs, tenant info) across async boundaries using `OutboxContextProvider` or
SpEL expressions in `@OutboxEvent`.

```kotlin
@Component
class TracingContextProvider(private val tracer: Tracer) : OutboxContextProvider {
    override fun provide() =
        mapOf("traceId" to tracer.currentSpan()?.context()?.traceId().orEmpty())
}
```

→ [Context Propagation Documentation](https://www.namastack.io/outbox/reference/context-propagation/)

### Messaging Integrations

Ready-to-use modules for Kafka, RabbitMQ, and AWS SNS with flexible routing, header mapping, and
payload transformation.

```gradle
implementation("io.namastack:namastack-outbox-kafka:1.4.0")
implementation("io.namastack:namastack-outbox-rabbit:1.4.0")
implementation("io.namastack:namastack-outbox-sns:1.4.0")
```

→ [Messaging Documentation](https://www.namastack.io/outbox/reference/messaging/)

---

## How It Works

### The Transactional Outbox Pattern

The outbox record is saved **in the same database transaction** as your business data. A background
scheduler polls for new records and dispatches them to handlers. This guarantees atomicity - either
both succeed or both fail together.

### Partitioning & Scaling

Records are distributed across **256 partitions** using consistent hashing on the record key. Each
application instance is assigned a subset of partitions, enabling horizontal scaling while
maintaining ordering guarantees per key.

```
Instance 1 → partitions 0-84    → processes "order-123", "order-456"
Instance 2 → partitions 85-169  → processes "payment-789"
Instance 3 → partitions 170-255 → processes other keys
```

When instances join or leave, partitions are automatically rebalanced. Stale instances are detected
via heartbeats and their partitions are reassigned.

→ [Core and Partitioning Documentation](https://www.namastack.io/outbox/reference/core/)<br/>
→ [Processing Documentation](https://www.namastack.io/outbox/reference/processing/)

---

## Observability & Operations

### Metrics (Micrometer)

```
outbox.records.count{status="new|failed|completed"}
outbox.partitions.assigned.count
outbox.partitions.pending.records.total
outbox.partitions.pending.records.max
```

### Distributed Tracing

Add `namastack-outbox-tracing` for automatic Micrometer Observation spans on every handler
invocation with trace context propagation across the async boundary.

### Actuator Endpoint

Add `namastack-outbox-actuator` for a management endpoint to query and clean up outbox records by
status.

→ [Observability Documentation](https://www.namastack.io/outbox/reference/observability/)

---

## Auto-Configuration Highlights

The library auto-configures everything you need with sensible defaults:

| Feature                        | Default                           | Property                                               |
|--------------------------------|-----------------------------------|--------------------------------------------------------|
| Outbox enabled                 | `true`                            | `namastack.outbox.enabled`                             |
| `@EnableScheduling` activation | Automatic (if not already active) | -                                                      |
| Polling strategy               | Fixed (2s interval)               | `namastack.outbox.polling.trigger`                     |
| Virtual threads                | Auto-detected                     | `spring.threads.virtual.enabled`                       |
| Delete completed records       | `false`                           | `namastack.outbox.processing.delete-completed-records` |
| Stop on first failure          | `true`                            | `namastack.outbox.processing.stop-on-first-failure`    |
| `@OutboxEvent` multicaster     | `true`                            | `namastack.outbox.multicaster.enabled`                 |

→ [Configuration Reference](https://www.namastack.io/outbox/reference/configuration/)

---

## Supported Databases

| Database   |  Auto Schema  | Tested |
|------------|:-------------:|:------:|
| H2         |       ✅       |   ✅    |
| MySQL      |       ✅       |   ✅    |
| MariaDB    |       ✅       |   ✅    |
| PostgreSQL |       ✅       |   ✅    |
| SQL Server |       ✅       |   ✅    |
| Oracle     |       ✅       |   ✅    |
| MongoDB    |       ✅       |   ✅    |

Schema files for
Flyway/Liquibase: [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

MongoDB setup script: [mongodb-setup.js on GitHub](https://github.com/namastack/namastack-outbox/blob/main/namastack-outbox-mongodb/src/main/resources/schema/mongodb-setup.js)

---

## Example Projects

| Example                                                                                           | Description                                  |
|---------------------------------------------------------------------------------------------------|----------------------------------------------|
| [example-h2](namastack-outbox-examples/namastack-outbox-example-h2)                               | Basic setup with H2 - perfect starting point |
| [example-java](namastack-outbox-examples/namastack-outbox-example-java)                           | Pure Java implementation                     |
| [example-annotation](namastack-outbox-examples/namastack-outbox-example-annotation)               | Annotation-based handler registration        |
| [example-kafka](namastack-outbox-examples/namastack-outbox-example-kafka)                         | Kafka integration                            |
| [example-rabbit](namastack-outbox-examples/namastack-outbox-example-rabbit)                       | RabbitMQ integration                         |
| [example-sns](namastack-outbox-examples/namastack-outbox-example-sns)                             | AWS SNS integration                          |
| [example-retry](namastack-outbox-examples/namastack-outbox-example-retry)                         | Retry policies                               |
| [example-fallback](namastack-outbox-examples/namastack-outbox-example-fallback)                   | Fallback handlers                            |
| [example-tracing](namastack-outbox-examples/namastack-outbox-example-tracing)                     | Distributed tracing with Micrometer          |
| [example-flyway-jpa](namastack-outbox-examples/namastack-outbox-example-flyway-jpa)               | Flyway schema management                     |
| [example-table-prefix-jdbc](namastack-outbox-examples/namastack-outbox-example-table-prefix-jdbc) | Custom table prefixes                        |
| [example-mongodb](namastack-outbox-examples/namastack-outbox-example-mongodb)                     | MongoDB with custom collection prefixes      |

→ [All examples](namastack-outbox-examples)

---

## Requirements

- Java 17+
- Spring Boot 4.0.0+
- Kotlin 2.2+ (optional, Java is fully supported)

---

## Support

- [Documentation](https://www.namastack.io/outbox/)
- [Issues](https://github.com/namastack/namastack-outbox/issues)
- [Discussions](https://github.com/namastack/namastack-outbox/discussions)
- [Example Projects](namastack-outbox-examples)

---

## License

Apache License 2.0 - See [LICENSE](./LICENSE)

---

## Acknowledgments

- Built with ❤️ by [Namastack](https://www.namastack.io)
- Inspired by
  the [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- Powered by Spring Boot & Kotlin
