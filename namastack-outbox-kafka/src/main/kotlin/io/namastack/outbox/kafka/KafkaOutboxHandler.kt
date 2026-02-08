package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaOperations
import java.util.concurrent.ExecutionException

/**
 * Outbox handler that sends payloads to Kafka topics.
 *
 * Uses [KafkaOutboxRouting] to determine the topic, key, headers,
 * payload mapping, and filtering for each payload.
 *
 * This handler is autoconfigured when Spring Kafka is on the classpath.
 * You only need to provide a custom [KafkaOutboxRouting] bean if you
 * want to override the default routing behavior.
 *
 * ## Example Custom Routing (Kotlin)
 *
 * ```kotlin
 * @Configuration
 * class KafkaOutboxConfig {
 *
 *     @Bean
 *     fun kafkaOutboxRouting() = kafkaOutboxRouting {
 *         route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *             target("orders")
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
 * ## Example Custom Routing (Java)
 *
 * ```java
 * @Configuration
 * public class KafkaOutboxConfig {
 *
 *     @Bean
 *     public KafkaOutboxRouting kafkaOutboxRouting() {
 *         return KafkaOutboxRouting.builder()
 *             .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *                 .target("orders")
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
 * @param kafkaOperations Spring Kafka operations for sending messages
 * @param routing Configuration for routing payloads to topics
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaOutboxHandler(
    private val kafkaOperations: KafkaOperations<Any, Any>,
    private val routing: KafkaOutboxRouting,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(KafkaOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        if (!routing.shouldExternalize(payload, metadata)) {
            logger.debug("Skipping outbox record due to filter: handlerId={}", metadata.handlerId)
            return
        }

        val topic = routing.resolveTarget(payload, metadata)
        val key = routing.extractKey(payload, metadata)
        val headers = routing.buildHeaders(payload, metadata)
        val mappedPayload = routing.mapPayload(payload, metadata)

        val record = createProducerRecord(topic, key, mappedPayload, headers)

        logger.debug("Sending outbox record to Kafka: topic={}, key={}, handlerId={}", topic, key, metadata.handlerId)

        send(record, topic, key, metadata)
    }

    private fun createProducerRecord(
        topic: String,
        key: String?,
        payload: Any,
        headers: Map<String, String>,
    ): ProducerRecord<Any, Any> {
        val kafkaHeaders = headers.map { (k, v) -> RecordHeader(k, v.toByteArray(Charsets.UTF_8)) }

        return ProducerRecord(topic, null, key, payload, kafkaHeaders)
    }

    private fun send(
        record: ProducerRecord<Any, Any>,
        topic: String,
        key: String?,
        metadata: OutboxRecordMetadata,
    ) {
        try {
            val result = kafkaOperations.send(record).get()
            logger.debug(
                "Successfully sent outbox record to Kafka: topic={}, partition={}, offset={}",
                result.recordMetadata.topic(),
                result.recordMetadata.partition(),
                result.recordMetadata.offset(),
            )
        } catch (ex: ExecutionException) {
            logger.error(
                "Failed to send outbox record to Kafka: topic={}, key={}, handlerId={}",
                topic,
                key,
                metadata.handlerId,
                ex.cause,
            )
            throw ex.cause ?: ex
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error(
                "Interrupted while sending outbox record to Kafka: topic={}, key={}, handlerId={}",
                topic,
                key,
                metadata.handlerId,
                ex,
            )
            throw ex
        }
    }
}
