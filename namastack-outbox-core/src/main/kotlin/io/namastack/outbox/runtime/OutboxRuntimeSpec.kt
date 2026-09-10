package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import java.time.Clock

/**
 * Fully resolved inputs used to construct exactly one programmatic outbox runtime.
 *
 * This specification contains no bean names, configuration binding, persistence selection, or
 * runtime multiplicity. Instrumentation and channel identity default to the values already used by
 * [handlerInfrastructure], keeping scheduling and handler dispatch consistent.
 *
 * @property properties Runtime-local outbox configuration
 * @property persistence Resolved persistence repositories and ownership
 * @property handlerInfrastructure Isolated handler registries and invokers
 * @property contextCollector Collector for scheduling context
 * @property resources Resolved executor, schedulers, and ownership
 * @property clock Clock used by the complete runtime graph
 * @property instrumentation Instrumentation applied to scheduling operations
 * @property channelNameProvider Provider for the logical outbox channel name
 * @property observationRegistry Supplier for scheduled-task observations
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
    val observationRegistry: () -> ObservationRegistry = { ObservationRegistry.NOOP },
) {
    /** Instrumentation shared by scheduling and handler dispatch. */
    val instrumentation: OutboxInstrumentation
        get() = handlerInfrastructure.instrumentation

    /** Logical channel provider shared by scheduling and handler dispatch. */
    val channelNameProvider: OutboxChannelNameProvider
        get() = handlerInfrastructure.channelNameProvider
}
