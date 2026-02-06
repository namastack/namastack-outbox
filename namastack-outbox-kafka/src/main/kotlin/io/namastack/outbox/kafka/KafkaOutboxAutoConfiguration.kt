package io.namastack.outbox.kafka

import io.namastack.outbox.OutboxCoreAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaOperations

/**
 * Auto-configuration for Kafka outbox integration.
 *
 * Automatically configures [KafkaOutboxHandler] when:
 * - Spring Kafka is on the classpath
 * - A [KafkaOperations] bean is available
 * - Property `namastack.outbox.kafka.enabled` is `true` (default)
 *
 * Users can override the default configuration by providing their own
 * [KafkaRoutingConfiguration] or [KafkaOutboxHandler] beans.
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
 * For full control, provide your own [KafkaRoutingConfiguration] bean:
 *
 * ```kotlin
 * @Bean
 * fun kafkaRoutingConfiguration() = KafkaRoutingConfiguration.create()
 *     .routing {
 *         route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *             topic("orders")
 *             key { event, _ -> (event as OrderEvent).orderId }
 *         }
 *         defaults {
 *             topic("domain-events")
 *         }
 *     }
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@AutoConfiguration(after = [OutboxCoreAutoConfiguration::class])
@ConditionalOnClass(KafkaOperations::class)
@ConditionalOnProperty(name = ["namastack.outbox.kafka.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KafkaOutboxProperties::class)
class KafkaOutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun kafkaRoutingConfiguration(properties: KafkaOutboxProperties): KafkaRoutingConfiguration =
        KafkaRoutingConfiguration
            .create()
            .routing {
                defaults {
                    topic(properties.defaultTopic)
                }
            }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaOperations::class)
    fun kafkaOutboxHandler(
        kafkaOperations: KafkaOperations<String, Any>,
        routingConfiguration: KafkaRoutingConfiguration,
    ): KafkaOutboxHandler = KafkaOutboxHandler(kafkaOperations, routingConfiguration)
}
