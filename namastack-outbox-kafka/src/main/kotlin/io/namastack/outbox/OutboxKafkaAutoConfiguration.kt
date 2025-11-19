package io.namastack.outbox

import io.namastack.outbox.routing.RoutingConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaOperations

@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxKafkaAutoConfiguration {
    @Bean
    @Primary
    fun kafkaOutboxRecordProcessor(
        routingConfiguration: RoutingConfiguration,
        kafkaOperations: KafkaOperations<Any, Any>,
    ): OutboxRecordProcessor = KafkaOutboxRecordProcessor(routingConfiguration, kafkaOperations)
}
