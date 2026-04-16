package io.namastack.outbox.sns

import io.awspring.cloud.sns.core.SnsOperations
import io.awspring.cloud.sns.core.SnsTemplate
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for SNS outbox integration.
 *
 * Automatically configures [SnsOutboxHandler] when:
 * - Spring Cloud AWS SNS is on the classpath
 * - A [SnsOperations] bean is available
 * - Property `namastack.outbox.sns.enabled` is `true` (default)
 *
 * Users can override the default configuration by providing their own
 * [SnsOutboxRouting] or [SnsOutboxHandler] beans.
 *
 * ## Default behavior
 *
 * Without any routing configuration:
 * - Topic ARN defaults to `arn:aws:sns:us-east-1:000000000000:outbox-events`
 *   (or configured via `namastack.outbox.sns.default-topic-arn`)
 * - Message group ID is taken from `metadata.key`
 *
 * ## Configuration properties
 *
 * ```yaml
 * namastack:
 *   outbox:
 *     sns:
 *       enabled: true
 *       default-topic-arn: arn:aws:sns:us-east-1:123456789012:my-topic
 * ```
 *
 * ## Custom routing
 *
 * For full control, provide your own [SnsOutboxRouting] bean:
 *
 * ```kotlin
 * @Bean
 * fun snsOutboxRouting() = snsOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("arn:aws:sns:us-east-1:123456789012:orders")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *     }
 *     defaults {
 *         target("arn:aws:sns:us-east-1:123456789012:domain-events")
 *     }
 * }
 * ```
 *
 * @author Roland Beisel
 * @since 1.3.0
 */
@AutoConfiguration
@ConditionalOnClass(SnsTemplate::class)
@ConditionalOnProperty(name = ["namastack.outbox.sns.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SnsOutboxProperties::class)
class SnsOutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun snsOutboxRouting(properties: SnsOutboxProperties): SnsOutboxRouting =
        snsOutboxRouting {
            defaults {
                target(properties.defaultTopicArn)
            }
        }

    @Bean
    fun snsOutboxHandler(
        snsOperations: SnsOperations,
        routingConfiguration: SnsOutboxRouting,
    ): SnsOutboxHandler = SnsOutboxHandler(snsOperations, routingConfiguration)
}
