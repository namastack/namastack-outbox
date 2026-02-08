package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.kafka.KafkaOutboxRouting
import io.namastack.outbox.kafka.kafkaOutboxRouting
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.type
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaOutboxRoutingConfiguration {
    @Bean
    fun kafkaRouting(): KafkaOutboxRouting =
        kafkaOutboxRouting {
            route(type(CustomerRegisteredEvent::class.java)) {
                target("customer-registrations")
                key { _, metadata -> metadata.key }
                headers { payload, _ ->
                    mapOf(
                        "CustomerMail" to (payload as CustomerRegisteredEvent).email,
                    )
                }
            }
            defaults {
                target("default-topic")
                key { _, metadata -> metadata.key }
                headers { payload, _ -> mapOf("eventType" to payload.javaClass.simpleName) }
            }
        }
}
