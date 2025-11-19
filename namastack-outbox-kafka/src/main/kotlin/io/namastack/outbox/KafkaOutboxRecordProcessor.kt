package io.namastack.outbox

import io.namastack.outbox.routing.RoutingConfiguration
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.KafkaHeaders.KEY
import org.springframework.kafka.support.KafkaHeaders.TOPIC
import org.springframework.messaging.support.MessageBuilder

class KafkaOutboxRecordProcessor(
    private val routingConfiguration: RoutingConfiguration,
    private val kafkaOperations: KafkaOperations<Any, Any>,
) : OutboxRecordProcessor {
    override fun process(record: OutboxRecord) {
        val route = routingConfiguration.getRoute(record.eventType)
        val targetRouting = route.target.invoke(record)
        val payload = route.mapper.invoke(record)
        val headers = route.headers.invoke(record)

        val messageBuilder =
            MessageBuilder
                .withPayload(payload)
                .copyHeadersIfAbsent(headers)
                .setHeaderIfAbsent(TOPIC, targetRouting.target)
                .setHeaderIfAbsent(KEY, targetRouting.key ?: record.aggregateId)

        val message = messageBuilder.build()

        kafkaOperations.send(message).get()
    }
}
