package io.namastack.outbox.rabbit

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessagePostProcessor
import org.springframework.amqp.rabbit.core.RabbitTemplate

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
 * @param rabbitTemplate Spring AMQP template for sending messages and configuring returns callback
 * @param routing configuration for routing payloads to exchanges
 * @author Roland Beisel
 * @since 1.1.0
 */
class RabbitOutboxHandler(
    private val rabbitTemplate: RabbitTemplate,
    private val routing: RabbitOutboxRouting,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(RabbitOutboxHandler::class.java)

    init {
        rabbitTemplate.setMandatory(true)
        rabbitTemplate.setReturnsCallback { returned ->
            throw AmqpException(
                "Unroutable message - misconfiguration suspected, retrying will not help: " +
                    "exchange=${returned.exchange}, routingKey=${returned.routingKey}, " +
                    "replyCode=${returned.replyCode}, replyText=${returned.replyText}",
            )
        }
    }

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

        val mappedPayload = routing.mapPayload(payload, metadata)
        val exchange = routing.resolveExchange(payload, metadata)
        val routingKey = routing.extractKey(payload, metadata)
        val headers = routing.buildHeaders(payload, metadata)

        logger.debug(
            "Sending outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
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
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, messagePostProcessor(headers))
        } catch (ex: AmqpException) {
            logger.error(
                "Failed to send outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
                exchange,
                routingKey,
                metadata.handlerId,
                ex,
            )
            throw ex
        }
    }

    private fun messagePostProcessor(headers: Map<String, String>): MessagePostProcessor =
        MessagePostProcessor { message ->
            headers.forEach { (name, value) ->
                message.messageProperties.setHeader(name, value)
            }
            message
        }
}
