package io.namastack.springoutbox

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxActuatorAutoConfiguration {
    @Bean
    fun outboxActuatorEndpoint(
        outboxRecordRepository: ObjectProvider<OutboxRecordRepository>,
    ): OutboxActuatorEndpoint {
        val repository =
            outboxRecordRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordRepository bean is missing! The Outbox actuator endpoints cannot be registered because no persistence module (e.g. spring-outbox-jpa) is included. Please add a persistence module to your dependencies to enable Outbox metrics.",
                )

        return OutboxActuatorEndpoint(repository)
    }
}
