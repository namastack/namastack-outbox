package io.namastack.outbox.observability

import io.namastack.outbox.Outbox
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides a custom observation convention that depends directly on the configured outbox.
 *
 * @author Roland Beisel
 */
@Configuration
internal class OutboxDependentConventionConfiguration {
    /**
     * Creates the scheduling convention after the outbox has completed initialization.
     *
     * @param outbox Configured outbox dependency
     * @return Custom scheduling observation convention
     */
    @Bean
    fun outboxScheduleObservationConvention(outbox: Outbox): OutboxScheduleObservationConvention =
        object : OutboxScheduleObservationConvention {
            override fun getName(): String = "custom.schedule"
        }
}
