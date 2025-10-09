package io.namastack.springoutbox

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration class for Spring Outbox Actuator functionality.
 *
 * This configuration provides actuator endpoints for administrative operations
 * on outbox records when the EnableOutbox annotation is present.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxActuatorAutoConfiguration {
    /**
     * Creates the outbox actuator endpoint when an outbox record repository is available.
     *
     * @param outboxRecordRepository Provider for the outbox record repository
     * @return Configured outbox actuator endpoint
     * @throws IllegalStateException if no outbox record repository is available
     */
    @Bean
    fun outboxActuatorEndpoint(
        outboxRecordRepository: ObjectProvider<OutboxRecordRepository>,
    ): OutboxActuatorEndpoint {
        val repository =
            outboxRecordRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordRepository bean is missing! The Outbox actuator endpoints cannot be registered because no persistence module (e.g. spring-outbox-jpa) is included. Please add a persistence module to your dependencies to enable Outbox actuator endpoints.",
                )

        return OutboxActuatorEndpoint(repository)
    }
}
