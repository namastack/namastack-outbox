package io.namastack.outbox.rabbit

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory

/**
 * Outbox handler that prepares payloads for RabbitMQ publication.
 *
 * Uses [RabbitOutboxRouting] to determine the exchange, routing key, headers,
 * payload mapping, and filtering for each payload. Actual RabbitMQ publication is
 * delegated to [RabbitOutboxPublisher].
 *
 * This handler is auto-configured when Spring AMQP is on the classpath.
 * You only need to provide a custom [RabbitOutboxRouting] bean if you
 * want to override the default routing behavior.
 *
 * ## Example custom routing (Kotlin)
 *
 * ```kotlin
 * @Configuration
 * class RabbitOutboxConfig {
 *
 *     @Bean
 *     fun rabbitOutboxRouting() = rabbitOutboxRouting {
 *         route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *             target("orders-exchange")
 *             key { payload, _ -> (payload as OrderEvent).orderId }
 *             mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
 *             filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
 *         }
 *         defaults {
 *             target("domain-events")
 *         }
 *     }
 * }
 * ```
 *
 * ## Example custom routing (Java)
 *
 * ```java
 * @Configuration
 * public class RabbitOutboxConfig {
 *
 *     @Bean
 *     public RabbitOutboxRouting rabbitOutboxRouting() {
 *         return RabbitOutboxRouting.builder()
 *             .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *                 .target("orders-exchange")
 *                 .key((payload, metadata) -> ((OrderEvent) payload).getOrderId())
 *                 .mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent())
 *                 .filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"))
 *             )
 *             .defaults(route -> route.target("domain-events"))
 *             .build();
 *     }
 * }
 * ```
 *
 * @param publisher Rabbit outbox publisher for broker interaction
 * @param routing configuration for routing payloads to exchanges
 * @author Roland Beisel
 * @since 1.1.0
 */
class RabbitOutboxHandler(
    private val publisher: RabbitOutboxPublisher,
    private val routing: RabbitOutboxRouting,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(RabbitOutboxHandler::class.java)

    override fun supports(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = routing.shouldExternalize(payload, metadata)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        if (!supports(payload, metadata)) {
            logger.debug("Skipping outbox record due to filter: handlerId={}", metadata.handlerId)
            return
        }

        val message = buildMessage(payload, metadata)

        logger.debug(
            "Sending outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
            message.exchange,
            message.routingKey,
            message.handlerId,
        )

        publisher.publish(message)
    }

    private fun buildMessage(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): RabbitOutboxMessage =
        RabbitOutboxMessage(
            payload = routing.mapPayload(payload, metadata),
            exchange = routing.resolveExchange(payload, metadata),
            routingKey = routing.extractKey(payload, metadata) ?: "",
            headers = routing.buildHeaders(payload, metadata),
            handlerId = metadata.handlerId,
        )
}
