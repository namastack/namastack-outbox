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
 * Uses [KafkaRoutingConfiguration] to determine the topic, key, and headers
 * for each payload. This is a generic handler that processes all outbox records.
 *
 * ## Example Configuration
 *
 * ```kotlin
 * @Configuration
 * class KafkaOutboxConfig {
 *
 *     @Bean
 *     fun kafkaRoutingConfiguration() = KafkaRoutingConfiguration.create()
 *         .routing {
 *             route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *                 topic("orders")
 *                 key { event, _ -> (event as OrderEvent).orderId }
 *             }
 *             defaults {
 *                 topic("domain-events")
 *             }
 *         }
 *
 *     @Bean
 *     fun kafkaOutboxHandler(
 *         kafkaOperations: KafkaOperations<String, Any>,
 *         routingConfiguration: KafkaRoutingConfiguration,
 *     ) = KafkaOutboxHandler(kafkaOperations, routingConfiguration)
 * }
 * ```
 *
 * @param kafkaOperations Spring Kafka operations for sending messages
 * @param routingConfiguration Configuration for routing payloads to topics
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaOutboxHandler(
    private val kafkaOperations: KafkaOperations<String, Any>,
    private val routingConfiguration: KafkaRoutingConfiguration,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(KafkaOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        val topic = routingConfiguration.resolveTopic(payload, metadata)
        val key = routingConfiguration.extractKey(payload, metadata)
        val headers = routingConfiguration.buildHeaders(payload, metadata)

        val record = ProducerRecord<String, Any>(topic, null, key, payload, buildKafkaHeaders(headers))

        logger.debug(
            "Sending outbox record to Kafka: topic={}, key={}, handlerId={}",
            topic,
            key,
            metadata.handlerId,
        )

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

    private fun buildKafkaHeaders(headers: Map<String, String>): List<RecordHeader> =
        headers.map { (key, value) -> RecordHeader(key, value.toByteArray(Charsets.UTF_8)) }
}
