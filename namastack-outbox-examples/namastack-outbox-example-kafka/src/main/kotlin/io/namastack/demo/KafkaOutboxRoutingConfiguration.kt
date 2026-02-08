package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.kafka.KafkaOutboxRouting
import io.namastack.outbox.kafka.kafkaRouting
import io.namastack.outbox.kafka.topic
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.type
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaOutboxRoutingConfiguration {
    @Bean
    fun kafkaOutboxRouting(): KafkaOutboxRouting =
        kafkaRouting {
            route(type(CustomerRegisteredEvent::class.java)) {
                topic("customer-registrations")
                key { _, metadata -> metadata.key }
                headers { payload, _ ->
                    mapOf(
                        "CustomerMail" to (payload as CustomerRegisteredEvent).email,
                    )
                }
            }
            defaults {
                topic("default-topic")
                key { _, metadata -> metadata.key }
                headers { payload, _ -> mapOf("eventType" to payload.javaClass.simpleName) }
            }
        }
}
