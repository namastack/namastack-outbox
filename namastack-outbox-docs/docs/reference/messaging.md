---
custom_edit_url: null
pagination_prev: null
pagination_next: null
title: Messaging Integrations
description: Ready-to-use handlers and configuration for Kafka, RabbitMQ, and SNS integrations.
sidebar_position: 4.5
---

# Messaging Integrations

Namastack Outbox provides first-class support for sending outbox events to Kafka, RabbitMQ, and 
AWS SNS. These modules offer ready-to-use handlers, flexible routing, and simple configuration.

## Processing Groups and Message Keys

:::info Available since 1.12.0
Processing groups are available since version 1.12.0.
:::

Default routing uses the business key from handler metadata as the Kafka key, RabbitMQ routing
key, and SNS message group ID. The [processing group](processing-groups.md) is available as
`metadata.processingGroup` and is not added to messages unless a routing callback adds it, for
example as a header.

## Quickstart: Adding Kafka, RabbitMQ, or SNS Support

To use the Kafka, RabbitMQ, or SNS modules, simply add the corresponding dependency to your project:

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

<Tabs>
<TabItem value="gradle" label="Gradle (Kotlin DSL)">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation(platform("io.namastack:namastack-outbox-bom:{{versionLabel}}"))
      implementation("io.namastack:namastack-outbox-kafka")
      implementation("io.namastack:namastack-outbox-rabbit")
      implementation("io.namastack:namastack-outbox-sns")
}`} />

</TabItem>
<TabItem value="maven" label="Maven">

<VersionedCode language="xml" template= {`<dependencyManagement>
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

<dependencies>
    <!-- For Kafka integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-kafka</artifactId>
    </dependency>
    <!-- For RabbitMQ integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-rabbit</artifactId>
    </dependency>
    <!-- For SNS integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-sns</artifactId>
    </dependency>
</dependencies>`} />

</TabItem>
</Tabs>

All modules are optional and can be included as needed. They are auto-configured if the 
corresponding Spring integration is present on the classpath.

---

<Tabs>
<TabItem value="kafka" label="Kafka Integration">

- **Handler:** `KafkaOutboxHandler` automatically sends outbox events to Kafka topics.
- **Routing:** Customizable via a `KafkaOutboxRouting` bean. Define target topic, key, headers, payload mapping, and filtering per payload type.
- **Headers:** Use the `headers` configurer to set custom Kafka headers for each message.
- **Auto-configuration:** Enabled if Spring Kafka is present.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class KafkaOutboxConfig {
    @Bean
    fun kafkaOutboxRouting() = kafkaOutboxRouting {
        route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
            target("orders")
            key { payload, _ -> (payload as OrderEvent).orderId }
            headers { payload, metadata -> mapOf(
                "custom-header" to "value", 
                "traceId" to metadata.context["traceId"]) 
            }
            mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
            filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
        }
        defaults {
            target("domain-events")
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class KafkaOutboxConfig {
    @Bean
    public KafkaOutboxRouting kafkaOutboxRouting() {
        return KafkaOutboxRouting.builder()
            .route(OutboxPayloadSelector.type(OrderEvent.class), route -> {
                route.target("orders");
                route.key((payload, metadata) -> ((OrderEvent) payload).getOrderId());
                route.headers((payload, metadata) -> Map.of(
                    "custom-header", "value",
                    "traceId", metadata.getContext().get("traceId")
                ));
                route.mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent());
                route.filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"));
            })
            .defaults(route -> route.target("domain-events"))
            .build();
    }
}
```

</TabItem>
</Tabs>

**Configuration Properties**

| Property                                            | Default         | Description                                      |
|-----------------------------------------------------|-----------------|--------------------------------------------------|
| `namastack.outbox.kafka.enabled`                    | `true`          | Enable Kafka outbox integration                  |
| `namastack.outbox.kafka.default-topic`              | `outbox-events` | Default Kafka topic for outbox events            |
| `namastack.outbox.kafka.enable-json`                | `true`          | Enable JSON support for Kafka outbox integration |
| `namastack.outbox.kafka.idempotency-header-enabled` | `false`         | Add an `Idempotency-Key` header                  |

</TabItem>
<TabItem value="rabbit" label="RabbitMQ Integration">

- **Handler:** `RabbitOutboxHandler` automatically sends outbox events to RabbitMQ exchanges.
- **Routing:** Customizable via a `RabbitOutboxRouting` bean. Define target exchange, routing key, headers, payload mapping, and filtering per payload type.
- **Headers:** Use the `headers` configurer to set custom RabbitMQ headers for each message.
- **Auto-configuration:** Enabled if Spring AMQP is present.
- **Publisher confirms:** Requires `spring.rabbitmq.publisher-confirm-type=correlated`.

:::info RabbitMQ publisher confirms
RabbitMQ publishing uses synchronous correlated publisher confirms so an outbox record is only
completed after RabbitMQ confirms the publish. The detailed [RabbitMQ Integration](#rabbitmq-integration)
section covers required settings, routing, configuration, and failure semantics.
:::

</TabItem>
<TabItem value="sns" label="SNS Integration">

- **Handler:** `SnsOutboxHandler` automatically sends outbox events to AWS SNS topics.
- **Routing:** Customizable via a `SnsOutboxRouting` bean. Define target topic ARN, message group ID (key), message attributes (headers), payload mapping, and filtering per payload type.
- **Message Attributes:** Use the `headers` configurer to set custom SNS message attributes for each message.
- **Auto-configuration:** Enabled if Spring Cloud AWS SNS is present.

:::info FIFO Topics & Ordering
When using SNS FIFO topics, the `key` configurer sets the **message group ID**, which preserves ordering per key. Records with the same key are sent **synchronously**, so a failure on one record stops processing of subsequent records with the same key.
:::

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class SnsOutboxConfig {
    @Bean
    fun snsOutboxRouting() = snsOutboxRouting {
        route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
            target("arn:aws:sns:us-east-1:123456789012:orders.fifo")
            key { payload, _ -> (payload as OrderEvent).orderId }
            headers { payload, metadata -> mapOf(
                "custom-header" to "value",
                "traceId" to (metadata.context["traceId"] ?: ""))
            }
            mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
            filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
        }
        defaults {
            target("arn:aws:sns:us-east-1:123456789012:domain-events")
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class SnsOutboxConfig {
    @Bean
    public SnsOutboxRouting snsOutboxRouting() {
        return SnsOutboxRouting.builder()
            .route(OutboxPayloadSelector.type(OrderEvent.class), route -> {
                route.target("arn:aws:sns:us-east-1:123456789012:orders.fifo");
                route.key((payload, metadata) -> ((OrderEvent) payload).getOrderId());
                route.headers((payload, metadata) -> Map.of(
                    "custom-header", "value",
                    "traceId", metadata.getContext().getOrDefault("traceId", "")
                ));
                route.mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent());
                route.filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"));
            })
            .defaults(route -> route.target("arn:aws:sns:us-east-1:123456789012:domain-events"))
            .build();
    }
}
```

</TabItem>
</Tabs>

**Configuration Properties**

| Property                                             | Default                                            | Description                                      |
|------------------------------------------------------|----------------------------------------------------|--------------------------------------------------|
| `namastack.outbox.sns.enabled`                       | `true`                                             | Enable SNS outbox integration                    |
| `namastack.outbox.sns.default-topic-arn`             | `arn:aws:sns:us-east-1:000000000000:outbox-events` | Default SNS topic ARN for outbox events          |
| `namastack.outbox.sns.idempotency-header-enabled`    | `false`                                            | Add an `Idempotency-Key` message attribute       |
| `namastack.outbox.sns.fifo-deduplication-id-enabled` | `false`                                            | Supply a native deduplication ID for FIFO topics |

</TabItem>
</Tabs>

## Idempotency Keys

Since 1.12.0, each messaging integration can add an `Idempotency-Key` header or SNS message
attribute. The feature is disabled by default. Enable it independently for each integration:

```yaml
namastack:
  outbox:
    kafka:
      idempotency-header-enabled: true
    rabbit:
      idempotency-header-enabled: true
    sns:
      idempotency-header-enabled: true
      fifo-deduplication-id-enabled: false
```

`metadata.recordId` is nullable because scheduling predicates run before a record exists. During
record processing, the framework always supplies the persisted ID. If you invoke a messaging handler
manually with automatic idempotency headers enabled, provide a record ID or an explicit idempotency key;
a missing key is rejected.

The default value is `metadata.recordId`, the persisted ID of the outbox record. It stays the same
across retries. Each handler has a separate record ID, and scheduling an event again creates new
IDs. The ordering key (`metadata.key`) is unchanged.

Override the value with the existing routing DSL. `OutboxHeaders.IDEMPOTENCY_KEY` is the public
constant for the fixed header name:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
header(OutboxHeaders.IDEMPOTENCY_KEY) { payload, _ ->
    (payload as OrderEvent).eventId
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
route.header(OutboxHeaders.IDEMPOTENCY_KEY,
    (payload, metadata) -> ((OrderEvent) payload).getEventId());
```

</TabItem>
</Tabs>

You can also read a key from `metadata.context`, populated through `additionalContext` when
scheduling. Custom keys must identify an individual event or operation and stay stable across
retries. Empty or whitespace-only explicit keys are rejected before publication when the
relevant setting is enabled; they do not fall back to the record ID.

For a different header name, leave automatic idempotency headers disabled and configure it directly:

```kotlin
header("event-id") { _, metadata -> requireNotNull(metadata.recordId) }
```

Sending a header alone does not deduplicate messages. Consumers must use the key to detect and
handle duplicates.

### Native SNS FIFO Deduplication

`namastack.outbox.sns.fifo-deduplication-id-enabled=true` automatically supplies a native deduplication ID
for topics ending in `.fifo`. It works independently of automatic `Idempotency-Key` attributes.
Standard topics retain their existing behavior.

The native ID is resolved in this order:

1. An explicit `SnsHeaders.MESSAGE_DEDUPLICATION_ID_HEADER` route header.
2. An explicit `Idempotency-Key` route header.
3. The persisted `recordId`.

An explicit native ID does not replace the consumer-facing `Idempotency-Key` value. With automatic
FIFO deduplication IDs enabled, both explicit keys are checked for empty or whitespace-only values.
Existing route headers continue to be sent when the settings are disabled.

SNS suppresses duplicate IDs within a five-minute window. An explicit native ID overrides
SNS content-based deduplication. Consumers still need to handle duplicates outside that window.
See [AWS SNS message deduplication](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-dedup.html).

### Direct Handler Construction in 1.12.0

The messaging handlers now accept the new Boolean settings in their constructors. Java callers
constructing handlers directly must pass them explicitly, for example:

```java
new KafkaOutboxHandler(kafkaOperations, routing, false);
new RabbitOutboxHandler(publisher, routing, false);
new SnsOutboxHandler(snsOperations, routing, false, false);
```

Kotlin callers can omit these settings because they default to `false`. Existing compiled callers
of the old constructors must be recompiled. Direct calls to the auto-configuration handler factory
methods must now also supply the integration's properties. Spring-managed applications receive
these properties through auto-configuration.

## RabbitMQ Integration

The `namastack-outbox-rabbit` module provides a ready-to-use outbox handler for publishing
records to RabbitMQ exchanges.

RabbitMQ needs slightly more explicit configuration than some other brokers. A synchronous call
to Spring AMQP's `convertAndSend(...)` can fail immediately when the broker is unreachable, but
without publisher confirms it does not prove that RabbitMQ accepted the specific message. The
Rabbit outbox integration therefore requires correlated publisher confirms.

### Dependency

<Tabs>
<TabItem value="gradle" label="Gradle (Kotlin DSL)">

<VersionedCode language="kotlin" template= {`dependencies {
    implementation(platform("io.namastack:namastack-outbox-bom:{{versionLabel}}"))
    implementation("io.namastack:namastack-outbox-rabbit")
}`} />

</TabItem>
<TabItem value="maven" label="Maven">

<VersionedCode language="xml" template= {`<dependencyManagement>
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
    <artifactId>namastack-outbox-rabbit</artifactId>
</dependency>`} />

</TabItem>
</Tabs>

### Required RabbitMQ Settings

The Rabbit outbox publisher waits for a correlated publisher confirm before the outbox record is
considered successfully handled.

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

If this setting is missing, Rabbit outbox auto-configuration fails during startup with a clear
configuration error. This is intentional: without correlated publisher confirms, the outbox can
only know that the message was handed to the client/channel layer, not that RabbitMQ accepted the
publish.

### Optional Unroutable Message Detection

By default, Namastack Outbox treats the publish as successful when RabbitMQ confirms the message.
This means that publishing to an existing exchange can complete even if no queue is currently
bound for the routing key.

If your application wants unroutable messages to fail outbox processing, enable:

```yaml
namastack:
  outbox:
    rabbit:
      fail-on-unroutable: true

spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

When `fail-on-unroutable` is enabled:

- Spring AMQP publisher returns must be enabled.
- RabbitTemplate mandatory publishing must be enabled.
- Returned messages are converted into `RabbitOutboxSendException`.
- The outbox record remains retryable instead of being marked completed.

This mode is useful when missing bindings should be treated as deployment or routing
misconfiguration. Leave it disabled if publishing to an exchange without active bindings is a
valid state in your topology.

### What the Rabbit Outbox Guarantees

With correlated publisher confirms enabled, `RabbitOutboxPublisher` blocks until RabbitMQ confirms
the publish.

The handler fails and the outbox record is retried when:

- RabbitMQ nacks the publisher confirm.
- No publisher confirm arrives before `namastack.outbox.rabbit.publisher-confirm-timeout`.
- Spring AMQP fails while sending the message.
- Waiting for the confirm is interrupted.
- The message is returned and `namastack.outbox.rabbit.fail-on-unroutable=true`.

The handler does not guarantee that a downstream consumer has processed the message. The outbox
boundary ends when RabbitMQ accepts the publish. Consumer delivery and acknowledgements remain
the responsibility of RabbitMQ and the consuming applications.

### Configuration Properties

| Property                                             | Default         | Description                                                                                                                                                                       |
|------------------------------------------------------|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `namastack.outbox.rabbit.enabled`                    | `true`          | Enable Rabbit outbox integration.                                                                                                                                                 |
| `namastack.outbox.rabbit.default-exchange`           | `outbox-events` | Default Rabbit exchange for outbox events.                                                                                                                                        |
| `namastack.outbox.rabbit.enable-json`                | `true`          | Enable Jackson JSON message conversion for `RabbitTemplate`.                                                                                                                      |
| `namastack.outbox.rabbit.idempotency-header-enabled` | `false`         | Add an `Idempotency-Key` header.                                                                                                                                                  |
| `namastack.outbox.rabbit.publisher-confirm-timeout`  | `10s`           | Maximum time to wait for RabbitMQ publisher confirms.                                                                                                                             |
| `namastack.outbox.rabbit.fail-on-unroutable`         | `false`         | Whether returned unroutable messages should fail outbox processing. Requires `spring.rabbitmq.publisher-returns=true` and `spring.rabbitmq.template.mandatory=true` when enabled. |

### Routing

Routing is configured through a `RabbitOutboxRouting` bean. It controls the exchange, routing key,
headers, payload mapping, and filtering for each payload type.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class RabbitOutboxConfig {
    @Bean
    fun rabbitOutboxRouting() = rabbitOutboxRouting {
        route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
            target("orders-exchange")
            key { payload, _ -> (payload as OrderEvent).orderId }
            headers { payload, metadata ->
                mapOf(
                    "custom-header" to "value",
                    "traceId" to (metadata.context["traceId"] ?: ""),
                )
            }
            mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
            filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
        }
        defaults {
            target("domain-events")
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class RabbitOutboxConfig {
    @Bean
    public RabbitOutboxRouting rabbitOutboxRouting() {
        return RabbitOutboxRouting.builder()
            .route(OutboxPayloadSelector.type(OrderEvent.class), route -> {
                route.target("orders-exchange");
                route.key((payload, metadata) -> ((OrderEvent) payload).getOrderId());
                route.headers((payload, metadata) -> Map.of(
                    "custom-header", "value",
                    "traceId", metadata.getContext().getOrDefault("traceId", "")
                ));
                route.mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent());
                route.filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"));
            })
            .defaults(route -> route.target("domain-events"))
            .build();
    }
}
```

</TabItem>
</Tabs>

### Migration Note

Applications using `namastack-outbox-rabbit` must configure correlated publisher confirms:

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

Earlier versions used fire-and-forget publishing. That meant a record could be marked completed
even when RabbitMQ rejected the publish asynchronously, for example because the exchange did not
exist. The new behavior fails fast at startup if the required confirm setting is missing.

All modules are optional and can be included as needed. They provide a fast path to production-ready
messaging integration with minimal configuration.
