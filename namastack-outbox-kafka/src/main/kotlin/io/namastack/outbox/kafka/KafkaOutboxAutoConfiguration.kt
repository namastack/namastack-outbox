package io.namastack.outbox.kafka

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate

/**
 * Auto-configuration for Kafka outbox integration.
 *
 * Automatically configures [KafkaOutboxHandler] when:
 * - Spring Kafka is on the classpath
 * - A [KafkaOperations] bean is available
 * - Property `namastack.outbox.kafka.enabled` is `true` (default)
 *
 * Users can override the default configuration by providing their own
 * [KafkaOutboxRouting] or [KafkaOutboxHandler] beans.
 *
 * ## Default Behavior
 *
 * Without any configuration:
 * - Topic defaults to `outbox-events` (or configured via `default-topic`)
 * - Key is taken from `metadata.key`
 *
 * ## Configuration Properties
 *
 * ```yaml
 * namastack:
 *   outbox:
 *     kafka:
 *       enabled: true              # Enable/disable Kafka integration
 *       default-topic: my-events   # Override default topic
 * ```
 *
 * ## Custom Routing
 *
 * For full control, provide your own [KafkaOutboxRouting] bean:
 *
 * ```kotlin
 * @Bean
 * fun kafkaOutboxRouting() = kafkaOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders")
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
@ConditionalOnClass(KafkaTemplate::class)
@ConditionalOnProperty(name = ["namastack.outbox.kafka.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KafkaOutboxProperties::class)
class KafkaOutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun kafkaOutboxRouting(properties: KafkaOutboxProperties): KafkaOutboxRouting =
        kafkaOutboxRouting {
            defaults {
                target(properties.defaultTopic)
            }
        }

    @Bean
    fun kafkaOutboxHandler(
        kafkaOperations: KafkaOperations<Any, Any>,
        routingConfiguration: KafkaOutboxRouting,
    ): KafkaOutboxHandler = KafkaOutboxHandler(kafkaOperations, routingConfiguration)
}
