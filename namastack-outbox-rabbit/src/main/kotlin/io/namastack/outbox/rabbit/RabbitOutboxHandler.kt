package io.namastack.outbox.rabbit

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitMessageOperations
import java.util.concurrent.ExecutionException

/**
 * Outbox handler that sends payloads to RabbitMQ.
 *
 * Uses [RabbitOutboxRouting] to determine the exchange (target), routing key, headers,
 * payload mapping, and filtering for each payload.
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
 * @param rabbitMessageOperations Spring AMQP operations for sending messages
 * @param routing configuration for routing payloads to exchanges
 * @author Roland Beisel
 * @since 1.1.0
 */
class RabbitOutboxHandler(
    private val rabbitMessageOperations: RabbitMessageOperations,
    private val routing: RabbitOutboxRouting,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(RabbitOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        if (!routing.shouldExternalize(payload, metadata)) {
            logger.debug("Skipping outbox record due to filter: handlerId={}", metadata.handlerId)
            return
        }

        val mappedPayload = routing.mapPayload(payload, metadata)
        val exchange = routing.resolveExchange(payload, metadata)
        val routingKey = routing.extractKey(payload, metadata)
        val headers = routing.buildHeaders(payload, metadata)

        logger.debug(
            "Sending outbox record to Rabbit: topic={}, key={}, handlerId={}",
            exchange,
            routingKey,
            metadata.handlerId,
        )

        send(mappedPayload, exchange, routingKey, headers, metadata)
    }

    private fun send(
        payload: Any,
        exchange: String,
        routingKey: String?,
        headers: Map<String, String>,
        metadata: OutboxRecordMetadata,
    ) {
        try {
            rabbitMessageOperations.convertAndSend(exchange, routingKey, payload, headers)
        } catch (ex: ExecutionException) {
            logger.error(
                "Failed to send outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
                exchange,
                routingKey,
                metadata.handlerId,
                ex.cause,
            )
            throw ex.cause ?: ex
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error(
                "Interrupted while sending outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
                exchange,
                routingKey,
                metadata.handlerId,
                ex,
            )
            throw ex
        }
    }
}
