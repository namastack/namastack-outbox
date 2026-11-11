---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Spring Modulith Integration
description: Outbox-backed event externalization for Spring Modulith
sidebar_position: 4.5
slug: /reference/spring-modulith
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

# Spring Modulith Integration

Spring Modulith (2.1+) supports outbox-backed event externalization for relational databases through
its [Namastack integration](https://docs.spring.io/spring-modulith/reference/events.html#namastack-outbox-support).

This integration lets Spring Modulith applications publish externalized application events through
Namastack Outbox instead of relying only on Spring Modulith's Event Publication Registry.

## Why this integration exists

Spring Modulith's Event Publication Registry is useful for tracking incomplete event publications,
but it is not a full outbox implementation.

The registry records event publication state and allows failed publications to be resubmitted.
However, production outbox concerns such as retry orchestration, multi-instance coordination,
claiming, ordering, and operational dispatching are left to the application.

Namastack Outbox fills that gap.

With its Namastack Outbox integration, Spring Modulith can delegate event externalization to a
purpose-built outbox engine.

## What Namastack Outbox provides

Namastack Outbox is designed for reliable event publication in production environments.

It provides:

- transactional outbox persistence
- safe dispatching from multiple application instances
- retry handling for failed publications
- order-preserving publication
- operationally robust event delivery
- separation between domain transaction and external broker delivery

This makes it suitable for applications that run with multiple replicas, publish to external
brokers, and require reliable delivery semantics.

## How it works

When an application event is externalized by Spring Modulith, Spring Modulith's Namastack Outbox
integration records the outgoing event in the outbox.

The event is stored transactionally together with the business change. After the transaction
commits, Namastack Outbox takes responsibility for dispatching the event to the configured external
messaging infrastructure.

Conceptually:

1. Your application changes domain state.
2. A Spring Modulith application event is published.
3. Spring Modulith detects that the event is externalized.
4. In `outbox` mode, Spring Modulith delegates the event to Namastack Outbox.
5. Namastack Outbox stores the event transactionally.
6. Namastack Outbox dispatches the event reliably after commit.
7. Failed dispatch attempts are retried by Namastack Outbox.

## Event Publication Registry vs. Namastack Outbox

| Capability                                   | Spring Modulith Event Publication Registry | Namastack Outbox |
|----------------------------------------------|-------------------------------------------:|-----------------:|
| Tracks event publication state               |                                        Yes |              Yes |
| Persists publication intent transactionally  |                                        Yes |              Yes |
| Resubmits incomplete publications            |                                        Yes |              Yes |
| Coordinates multiple application instances   |                        User responsibility |              Yes |
| Handles retry orchestration                  |                        User responsibility |              Yes |
| Supports order-preserving publication        |                    Not the primary purpose |              Yes |
| Designed as production outbox infrastructure |                                         No |              Yes |

## When to use Namastack Outbox

Use Namastack Outbox with Spring Modulith when:

- your application runs with multiple instances
- events are sent to an external broker
- failed event publication must be retried automatically
- you need coordinated dispatch across instances
- event ordering matters
- you want outbox behavior without building your own dispatcher

## Migration in 3 Steps

### Step 1: Add Dependencies

Add Spring Modulith's Namastack starter and import the Namastack BOM to select **2.0.0** for its
transitive outbox dependencies. The Modulith starter alone may select an older Namastack version.

The examples use Spring Modulith `2.1.2`. Keep Spring Modulith aligned with your Spring Boot version
and rebuild and test the integration when changing either dependency.

**Choose your persistence layer:**

#### JPA-based Projects

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode version="2.0.0" language="kotlin" template= {`dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.2")
        mavenBom("io.namastack:namastack-outbox-bom:{{versionLabel}}")
    }
}

dependencies {
    // Your existing Spring Modulith dependencies
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    // Your transport dependencies (e.g., Kafka)
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    // NEW: Spring Modulith's Namastack Outbox integration
    implementation("org.springframework.modulith:spring-modulith-starter-namastack")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode version="2.0.0" language="xml" template= {`<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.namastack</groupId>
            <artifactId>namastack-outbox-bom</artifactId>
            <version>{{versionLabel}}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-bom</artifactId>
            <version>2.1.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Your existing Spring Modulith dependencies -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>
  </dependency>

  <!-- Your transport dependencies (e.g., Kafka) -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-events-kafka</artifactId>
  </dependency>

  <!-- NEW: Spring Modulith's Namastack Outbox integration -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-namastack</artifactId>
  </dependency>
</dependencies>`} />

</TabItem>
</Tabs>

#### JDBC-based Projects

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode version="2.0.0" language="kotlin" template= {`dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.2")
        mavenBom("io.namastack:namastack-outbox-bom:{{versionLabel}}")
    }
}

dependencies {
    // Your existing Spring Modulith dependencies
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    // Your transport dependencies (e.g., Kafka)
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    // NEW: Spring Modulith's Namastack Outbox integration
    implementation("org.springframework.modulith:spring-modulith-starter-namastack")
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode version="2.0.0" language="xml" template= {`<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.namastack</groupId>
            <artifactId>namastack-outbox-bom</artifactId>
            <version>{{versionLabel}}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-bom</artifactId>
            <version>2.1.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Your existing Spring Modulith dependencies -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-jdbc</artifactId>
    </dependency>
    
    <!-- Your transport dependencies (e.g., Kafka) -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-events-kafka</artifactId>
    </dependency>
    
    <!-- NEW: Spring Modulith's Namastack Outbox integration -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-namastack</artifactId>
    </dependency>
</dependencies>`} />

</TabItem>
</Tabs>

### Step 2: Enable Outbox Mode

Enable Outbox Mode in your `application.yml`:

```yaml
spring:
  modulith:
    events:
      externalization:
        mode: outbox  # NEW: Switch to Outbox Mode
```

With those Spring Modulith dependencies and `outbox` mode enabled, Spring Modulith routes
`@Externalized` events through Namastack Outbox.

### Step 3: Code Changes (Optional)

Your existing code works without any changes:

<Tabs>
<TabItem value="Kotlin" label="Kotlin">

```kotlin
@Externalized("payment-requests::#{orderId}")
data class PaymentRequestedEvent(
    val paymentId: UUID,
    val orderId: UUID,
    val amountCents: Long
)
```

```kotlin
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val events: ApplicationEventPublisher
) {

    @Transactional
    fun requestPayment(orderId: UUID, amountCents: Long): Payment {
        val payment = paymentRepository.save(
            Payment.request(orderId, amountCents)
        )

        // Event is automatically written to the Outbox
        events.publishEvent(
            PaymentRequestedEvent(
                paymentId = payment.id,
                orderId = payment.orderId,
                amountCents = payment.amountCents
            )
        )

        return payment
    }
}
```

</TabItem>
<TabItem value="Java" label="Java">

```java
@Externalized("payment-requests::#{orderId}")
public record PaymentRequestedEvent(
    UUID paymentId,
    UUID orderId,
    Long amountCents
) {}
```

```java
@Service
class PaymentService {

  @Transactional
  public Payment requestPayment(UUID orderId, Long amountCents) {
    var payment = paymentRepository.save(
        Payment.request(orderId, amountCents)
    );

    // Event is automatically written to the Outbox
    events.publishEvent(new PaymentRequestedEvent(
        payment.getId(),
        payment.getOrderId(),
        payment.getAmountCents()
    ));

    return payment;
  }
}
```

</TabItem>
</Tabs>

---

## Example Project

See the [Spring Modulith Outbox Example](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-examples/namastack-outbox-example-modulith) for a complete, runnable reference implementation.

This example demonstrates:
- Outbox-backed event externalization with Spring Modulith
- JDBC outbox persistence with JPA business entities
- Kafka integration
- End-to-end event flow and configuration

---

## Full Namastack Outbox Feature Set

The integration uses Namastack's processing runtime, including:

- Advanced retry and backoff strategies
- Partitioning and multi-instance coordination
- Observability, metrics, and tracing
- Fallback handling through application-defined handlers
- Transport integration through Spring Modulith
- Operational tooling and health endpoints

For details, see the [Namastack Outbox documentation](index.md) and the linked advanced
configuration topics above.

---

## Note on Messaging Integration

If you use Spring Modulith's messaging integration feature (e.g., Kafka, RabbitMQ, SNS), you do not
need to define your own outbox handler. The outbox handler is automatically provided by Modulith.
You can simply adjust the default retry policy and other outbox settings via configuration
properties to fit your requirements.

:::info Multicaster precedence

The `spring-modulith-events-namastack` module disables Namastack Outbox's `OutboxEventMulticaster`. Spring
Modulith's event multicaster takes precedence and is responsible for routing externalized events
through the outbox integration.

:::

See [Retry Policy](./retry.md) for details on customizing default retry behavior.

---

## Advanced Configuration

### Compile-Time Dependencies for Customization

The Spring Modulith dependencies above are enough to use outbox-backed externalization. Add
Namastack dependencies directly only when your application code customizes Namastack Outbox with
Namastack API types, such as defining an `OutboxRetryPolicy` bean or a custom `OutboxHandler`.

<Tabs>
<TabItem value="Gradle" label="Gradle">

<VersionedCode version="2.0.0" language="kotlin" template= {`dependencies {
    implementation(platform("io.namastack:namastack-outbox-bom:{{versionLabel}}"))
    implementation("io.namastack:namastack-outbox-api")
    // Add other Namastack modules only when you configure them directly
}`} />

</TabItem>
<TabItem value="Maven" label="Maven">

<VersionedCode version="2.0.0" language="xml" template= {`<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-bom</artifactId>
      <version>{{versionLabel}}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
    <groupId>io.namastack</groupId>
    <artifactId>namastack-outbox-api</artifactId>
</dependency>
<!-- Add other Namastack modules only when you configure them directly -->`} />

</TabItem>
</Tabs>

### Links

- [Retry Policy](./retry.md)
- [Observability & Metrics](./observability.md)
- [JDBC Support](./persistence.md)
