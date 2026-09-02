package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration that provides Micrometer Observation-based metrics and tracing
 * for the outbox processing pipeline.
 *
 * Activated when:
 * - [OutboxService] is on the classpath (core module present)
 * - An [ObservationRegistry] bean is available
 * - `namastack.outbox.enabled` is `true` (default)
 *
 * Provides observation-based instrumentation for:
 * - **Handler dispatch** (`outbox.record.process`) — timer per handler invocation
 * - **Record scheduling** (`outbox.record.schedule`) — timer per schedule call
 *
 * All observations include an `outbox.channel` low-cardinality tag supplied by the Core operation
 * boundary.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
@AutoConfiguration(
    after = [ObservationAutoConfiguration::class],
    before = [OutboxCoreInfrastructureAutoConfiguration::class],
)
@ConditionalOnClass(OutboxService::class, ObservationRegistry::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxObservabilityAutoConfiguration {
    /**
     * Provides the Micrometer implementation of the Core instrumentation contract.
     *
     * @param observationRegistry Registry used to create observations.
     * @param scheduleConvention Optional custom scheduling convention.
     * @param processConvention Optional custom processing convention.
     * @return The Micrometer outbox instrumentation.
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerOutboxInstrumentation::class)
    fun micrometerOutboxInstrumentation(
        observationRegistry: ObservationRegistry,
        scheduleConvention: ObjectProvider<OutboxScheduleObservationConvention>,
        processConvention: ObjectProvider<OutboxProcessObservationConvention>,
    ): MicrometerOutboxInstrumentation =
        MicrometerOutboxInstrumentation(
            observationRegistry = observationRegistry,
            customScheduleConventionSupplier = scheduleConvention::getIfAvailable,
            customProcessConventionSupplier = processConvention::getIfAvailable,
        )
}
