package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import java.time.Clock

/**
 * Fully resolved inputs for one outbox runtime.
 *
 * The specification contains no configuration binding, bean names, persistence selection, or
 * knowledge of other runtimes.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeSpec(
    val properties: OutboxProperties,
    val persistence: OutboxRuntimePersistence,
    val handlerInfrastructure: OutboxHandlerInfrastructure,
    val contextCollector: OutboxContextCollector,
    val resources: OutboxRuntimeResources,
    val clock: Clock,
    val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
)
