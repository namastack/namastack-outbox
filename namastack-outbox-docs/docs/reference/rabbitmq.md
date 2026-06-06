---
title: RabbitMQ Integration
description: RabbitMQ-specific publishing guarantees, required Spring AMQP settings, routing, and failure handling.
sidebar_position: 4.6
---

# RabbitMQ Integration

The `namastack-outbox-rabbit` module provides a ready-to-use outbox handler for publishing
records to RabbitMQ exchanges.

RabbitMQ needs slightly more explicit configuration than some other brokers. A synchronous call
to Spring AMQP's `convertAndSend(...)` can fail immediately when the broker is unreachable, but
without publisher confirms it does not prove that RabbitMQ accepted the specific message. The
Rabbit outbox integration therefore requires correlated publisher confirms.

## Dependency

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

<Tabs>
<TabItem value="gradle" label="Gradle (Kotlin DSL)">

<VersionedCode language="kotlin" template= {`dependencies {
    implementation("io.namastack:namastack-outbox-rabbit:{{versionLabel}}")
}`} />

</TabItem>
<TabItem value="maven" label="Maven">

<VersionedCode language="xml" template= {`<dependency>
    <groupId>io.namastack</groupId>
    <artifactId>namastack-outbox-rabbit</artifactId>
    <version>{{versionLabel}}</version>
</dependency>`} />

</TabItem>
</Tabs>

## Required RabbitMQ Settings

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

## Optional Unroutable Message Detection

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

## What the Rabbit Outbox Guarantees

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

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `namastack.outbox.rabbit.enabled` | `true` | Enable Rabbit outbox integration. |
| `namastack.outbox.rabbit.default-exchange` | `outbox-events` | Default Rabbit exchange for outbox events. |
| `namastack.outbox.rabbit.enable-json` | `true` | Enable Jackson JSON message conversion for `RabbitTemplate`. |
| `namastack.outbox.rabbit.publisher-confirm-timeout` | `10s` | Maximum time to wait for RabbitMQ publisher confirms. |
| `namastack.outbox.rabbit.fail-on-unroutable` | `false` | Whether returned unroutable messages should fail outbox processing. Requires `spring.rabbitmq.publisher-returns=true` and `spring.rabbitmq.template.mandatory=true` when enabled. |

## Routing

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

## Migration Note

Applications using `namastack-outbox-rabbit` must configure correlated publisher confirms:

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

Earlier versions used fire-and-forget publishing. That meant a record could be marked completed
even when RabbitMQ rejected the publish asynchronously, for example because the exchange did not
exist. The new behavior fails fast at startup if the required confirm setting is missing.
