package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.sns.SnsOutboxRouting
import io.namastack.outbox.sns.snsOutboxRouting
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.type
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SnsOutboxRoutingConfiguration {
    @Bean
    fun snsRouting(): SnsOutboxRouting =
        snsOutboxRouting {
            route(type(CustomerRegisteredEvent::class.java)) {
                target("arn:aws:sns:us-east-1:000000000000:customer-registrations")
                key { _, metadata -> metadata.key }
                headers { payload, _ ->
                    mapOf(
                        "CustomerMail" to (payload as CustomerRegisteredEvent).email,
                    )
                }
            }
            defaults {
                target("arn:aws:sns:us-east-1:000000000000:default-topic")
                key { _, metadata -> metadata.key }
                headers { payload, _ -> mapOf("eventType" to payload.javaClass.simpleName) }
            }
        }
}

