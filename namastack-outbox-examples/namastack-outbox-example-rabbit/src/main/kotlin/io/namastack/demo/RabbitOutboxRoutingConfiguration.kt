package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.rabbit.RabbitOutboxRouting
import io.namastack.outbox.rabbit.rabbitOutboxRouting
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.type
import org.springframework.amqp.core.Exchange
import org.springframework.amqp.core.ExchangeBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitOutboxRoutingConfiguration {
    @Bean
    fun customerRegistrationsExchange(): Exchange =
        ExchangeBuilder
            .topicExchange("customer-registrations")
            .durable(true)
            .build()

    @Bean
    fun defaultExchange(): Exchange =
        ExchangeBuilder
            .topicExchange("default-exchange")
            .durable(true)
            .build()

    @Bean
    fun rabbitRouting(): RabbitOutboxRouting =
        rabbitOutboxRouting {
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
                target("default-exchange")
                key { _, metadata -> metadata.key }
                headers { payload, _ -> mapOf("eventType" to payload.javaClass.simpleName) }
            }
        }
}
