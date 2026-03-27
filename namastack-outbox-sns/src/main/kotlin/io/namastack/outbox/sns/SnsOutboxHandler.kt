package io.namastack.outbox.sns

import io.awspring.cloud.sns.core.SnsNotification
import io.awspring.cloud.sns.core.SnsOperations
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException

/**
 * Outbox handler that sends payloads to AWS SNS topics.
 *
 * Uses [SnsOutboxRouting] to determine the topic ARN, message group ID (key), headers,
 * payload mapping, and filtering for each payload.
 *
 * This handler is auto-configured when Spring Cloud AWS SNS is on the classpath.
 * You only need to provide a custom [SnsOutboxRouting] bean if you want to override
 * the default routing behavior.
 *
 * ## Example custom routing (Kotlin)
 *
 * ```kotlin
 * @Configuration
 * class SnsOutboxConfig {
 *
 *     @Bean
 *     fun snsOutboxRouting() = snsOutboxRouting {
 *         route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *             target("arn:aws:sns:us-east-1:123456789012:orders")
 *             key { payload, _ -> (payload as OrderEvent).orderId }
 *             mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
 *             filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
 *         }
 *         defaults {
 *             target("arn:aws:sns:us-east-1:123456789012:domain-events")
 *         }
 *     }
 * }
 * ```
 *
 * ## Example custom routing (Java)
 *
 * ```java
 * @Configuration
 * public class SnsOutboxConfig {
 *
 *     @Bean
 *     public SnsOutboxRouting snsOutboxRouting() {
 *         return SnsOutboxRouting.builder()
 *             .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *                 .target("arn:aws:sns:us-east-1:123456789012:orders")
 *                 .key((payload, metadata) -> ((OrderEvent) payload).getOrderId())
 *                 .mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent())
 *                 .filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"))
 *             )
 *             .defaults(route -> route.target("arn:aws:sns:us-east-1:123456789012:domain-events"))
 *             .build();
 *     }
 * }
 * ```
 *
 * @param snsOperations Spring Cloud AWS SNS operations for sending messages
 * @param routing configuration for routing payloads to topic ARNs
 * @author Roland Beisel
 * @since 1.3.0
 */
class SnsOutboxHandler(
    private val snsOperations: SnsOperations,
    private val routing: SnsOutboxRouting,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(SnsOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        if (!routing.shouldExternalize(payload, metadata)) {
            logger.debug("Skipping outbox record due to filter: handlerId={}", metadata.handlerId)
            return
        }

        val mappedPayload = routing.mapPayload(payload, metadata)
        val topicArn = routing.resolveTopicArn(payload, metadata)
        val messageGroupId = routing.extractKey(payload, metadata)
        val headers = routing.buildHeaders(payload, metadata)

        logger.debug(
            "Sending outbox record to SNS: topicArn={}, messageGroupId={}, handlerId={}",
            topicArn,
            messageGroupId,
            metadata.handlerId,
        )

        send(mappedPayload, topicArn, messageGroupId, headers, metadata)
    }

    private fun send(
        payload: Any,
        topicArn: String,
        messageGroupId: String?,
        headers: Map<String, String>,
        metadata: OutboxRecordMetadata,
    ) {
        try {
            val builder = SnsNotification.builder(payload)

            headers.forEach { (key, value) -> builder.header(key, value) }

            if (messageGroupId != null) {
                builder.groupId(messageGroupId)
            }

            snsOperations.sendNotification(topicArn, builder.build())

            logger.debug(
                "Successfully sent outbox record to SNS: topicArn={}, messageGroupId={}",
                topicArn,
                messageGroupId,
            )
        } catch (ex: ExecutionException) {
            logger.error(
                "Failed to send outbox record to SNS: topicArn={}, messageGroupId={}, handlerId={}",
                topicArn,
                messageGroupId,
                metadata.handlerId,
                ex.cause,
            )
            throw ex.cause ?: ex
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error(
                "Interrupted while sending outbox record to SNS: topicArn={}, messageGroupId={}, handlerId={}",
                topicArn,
                messageGroupId,
                metadata.handlerId,
                ex,
            )
            throw ex
        }
    }
}
