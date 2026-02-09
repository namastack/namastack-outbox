package io.namastack.outbox.rabbit

import org.springframework.amqp.rabbit.core.RabbitMessageOperations
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for Rabbit outbox integration.
 *
 * Automatically configures [RabbitOutboxHandler] when:
 * - Spring AMQP is on the classpath
 * - A [RabbitMessageOperations] bean is available
 * - Property `namastack.outbox.rabbit.enabled` is `true` (default)
 *
 * Users can override the default configuration by providing their own
 * [RabbitOutboxRouting] or [RabbitOutboxHandler] beans.
 *
 * ## Default behavior
 *
 * Without any routing configuration:
 * - Exchange defaults to `outbox-events` (or configured via `namastack.outbox.rabbit.default-topic`)
 * - Routing key is taken from `metadata.key`
 *
 * ## Configuration properties
 *
 * ```yaml
 * namastack:
 *   outbox:
 *     rabbit:
 *       enabled: true                   # Enable/disable Rabbit integration
 *       default-topic: outbox-events    # Default exchange
 * ```
 *
 * ## Custom routing
 *
 * For full control, provide your own [RabbitOutboxRouting] bean:
 *
 * ```kotlin
 * @Bean
 * fun rabbitOutboxRouting() = rabbitOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders-exchange")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *     }
 *     defaults {
 *         target("domain-events")
 *     }
 * }
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate::class)
@ConditionalOnProperty(name = ["namastack.outbox.rabbit.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RabbitOutboxProperties::class)
class RabbitOutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun rabbitOutboxRouting(properties: RabbitOutboxProperties): RabbitOutboxRouting =
        rabbitOutboxRouting {
            defaults {
                target(properties.defaultExchange)
            }
        }

    @Bean
    fun rabbitOutboxHandler(
        rabbitMessageOperations: RabbitMessageOperations,
        routingConfiguration: RabbitOutboxRouting,
    ): RabbitOutboxHandler = RabbitOutboxHandler(rabbitMessageOperations, routingConfiguration)
}
