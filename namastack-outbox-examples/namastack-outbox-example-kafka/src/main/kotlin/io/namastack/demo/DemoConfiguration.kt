package io.namastack.demo

import io.namastack.outbox.routing.RoutingConfiguration
import io.namastack.outbox.routing.RoutingTarget
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DemoConfiguration {
    @Bean
    fun routingConfiguration(): RoutingConfiguration =
        RoutingConfiguration
            .builder()
            .routeAll {
                target { record ->
                    RoutingTarget
                        .forTarget("demo-topic")
                        .withKey(record.aggregateId)
                }
                mapper { record -> record.payload }
                headers { record -> mapOf("created_at" to record.createdAt.toString()) }
            }.build()
}
