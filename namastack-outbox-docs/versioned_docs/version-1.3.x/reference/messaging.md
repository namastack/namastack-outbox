---
title: Messaging Integrations
description: Ready-to-use handlers and configuration for Kafka, RabbitMQ, and SNS integrations.
sidebar_position: 4.5
---

# Messaging Integrations

Namastack Outbox provides first-class support for sending outbox events to Kafka, RabbitMQ, and AWS SNS. These modules offer ready-to-use handlers, flexible routing, and simple configuration.

## Quickstart: Adding Kafka, RabbitMQ, or SNS Support

To use the Kafka, RabbitMQ, or SNS modules, simply add the corresponding dependency to your project:

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

<Tabs>
<TabItem value="gradle" label="Gradle (Kotlin DSL)">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation("io.namastack:namastack-outbox-kafka:{{versionLabel}}")
      implementation("io.namastack:namastack-outbox-rabbit:{{versionLabel}}")
      implementation("io.namastack:namastack-outbox-sns:{{versionLabel}}")
}`} />

</TabItem>
<TabItem value="maven" label="Maven">

<VersionedCode language="xml" template= {`<dependencies>
    <!-- For Kafka integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-kafka</artifactId>
        <version>{{versionLabel}}</version>
    </dependency>
    <!-- For RabbitMQ integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-rabbit</artifactId>
        <version>{{versionLabel}}</version>
    </dependency>
    <!-- For SNS integration -->
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-sns</artifactId>
        <version>{{versionLabel}}</version>
    </dependency>
</dependencies>`} />

</TabItem>
</Tabs>

All modules are optional and can be included as needed. They are auto-configured if the corresponding Spring integration is present on the classpath.

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

| Property                               | Default         | Description                                      |
|----------------------------------------|-----------------|--------------------------------------------------|
| `namastack.outbox.kafka.enabled`       | `true`          | Enable Kafka outbox integration                  |
| `namastack.outbox.kafka.default-topic` | `outbox-events` | Default Kafka topic for outbox events            |
| `namastack.outbox.kafka.enable-json`   | `true`          | Enable JSON support for Kafka outbox integration |

</TabItem>
<TabItem value="rabbit" label="RabbitMQ Integration">

- **Handler:** `RabbitOutboxHandler` automatically sends outbox events to RabbitMQ exchanges.
- **Routing:** Customizable via a `RabbitOutboxRouting` bean. Define target exchange, routing key, headers, payload mapping, and filtering per payload type.
- **Headers:** Use the `headers` configurer to set custom RabbitMQ headers for each message.
- **Auto-configuration:** Enabled if Spring AMQP is present.

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
            headers { payload, metadata -> mapOf("custom-header" to "value", "traceId" to metadata.context["traceId"]) }
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
                route.headers((payload, metadata) -> Map.of("custom-header", "value", "traceId", metadata.getContext().get("traceId")));
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

| Property                                   | Default         | Description                                               |
|--------------------------------------------|-----------------|-----------------------------------------------------------|
| `namastack.outbox.rabbit.enabled`          | `true`          | Enable Rabbit outbox integration                          |
| `namastack.outbox.rabbit.default-exchange` | `outbox-events` | Default Rabbit exchange for outbox events                 |
| `namastack.outbox.rabbit.enable-json`      | `true`          | Enable Jackson JSON message conversion for RabbitTemplate |

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

| Property                                    | Default                                                    | Description                                      |
|---------------------------------------------|------------------------------------------------------------|--------------------------------------------------|
| `namastack.outbox.sns.enabled`              | `true`                                                     | Enable SNS outbox integration                    |
| `namastack.outbox.sns.default-topic-arn`    | `arn:aws:sns:us-east-1:000000000000:outbox-events`         | Default SNS topic ARN for outbox events          |

</TabItem>
</Tabs>

All modules are optional and can be included as needed. They provide a fast path to production-ready messaging integration with minimal configuration.
