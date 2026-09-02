package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxScheduleObservationConvention
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerKind
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * Instruments outbox scheduling and handler processing with Micrometer observations.
 *
 * Uses the existing outbox observation documentation, contexts, and conventions. Processing
 * observations retain the outbox record as their receiver carrier so stored propagation context
 * remains available to Micrometer tracing handlers.
 *
 * @param observationRegistrySupplier Lazy supplier for the registry used to create observations.
 * @param customScheduleConventionSupplier Lazy supplier for an optional custom scheduling observation convention.
 * @param customProcessConventionSupplier Lazy supplier for an optional custom processing observation convention.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class MicrometerOutboxInstrumentation(
    observationRegistrySupplier: () -> ObservationRegistry,
    customScheduleConventionSupplier: () -> OutboxScheduleObservationConvention? = { null },
    customProcessConventionSupplier: () -> OutboxProcessObservationConvention? = { null },
) : OutboxInstrumentation {
    private val observationRegistry: ObservationRegistry by lazy(SYNCHRONIZED) {
        observationRegistrySupplier()
    }
    private val resolvedScheduleConvention: OutboxScheduleObservationConvention? by lazy(SYNCHRONIZED) {
        customScheduleConventionSupplier()
    }
    private val resolvedProcessConvention: OutboxProcessObservationConvention? by lazy(SYNCHRONIZED) {
        customProcessConventionSupplier()
    }

    /**
     * Creates instrumentation with an already resolved registry.
     *
     * @param observationRegistry Registry used to create observations.
     * @param customScheduleConventionSupplier Lazy supplier for an optional custom scheduling observation convention.
     * @param customProcessConventionSupplier Lazy supplier for an optional custom processing observation convention.
     */
    constructor(
        observationRegistry: ObservationRegistry,
        customScheduleConventionSupplier: () -> OutboxScheduleObservationConvention? = { null },
        customProcessConventionSupplier: () -> OutboxProcessObservationConvention? = { null },
    ) : this(
        observationRegistrySupplier = { observationRegistry },
        customScheduleConventionSupplier = customScheduleConventionSupplier,
        customProcessConventionSupplier = customProcessConventionSupplier,
    )

    /**
     * Observes one outbox scheduling operation.
     *
     * @param invocation Description used to create the scheduling observation context.
     * @param action Scheduling action executed within the observation scope.
     */
    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) {
        val context =
            OutboxScheduleObservationContext(
                payloadType = invocation.payload::class.simpleName ?: "Unknown",
                recordKey = invocation.recordKey,
                channel = invocation.channel,
            )

        OutboxObservationDocumentation.OUTBOX_RECORD_SCHEDULE
            .observation(
                resolvedScheduleConvention,
                DefaultOutboxScheduleObservationConvention.INSTANCE,
                { context },
                observationRegistry,
            ).observe(action)
    }

    /**
     * Observes one primary or fallback handler invocation.
     *
     * @param invocation Description used to create the processing observation context.
     * @param action Handler action executed within the observation scope.
     */
    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) {
        val context =
            OutboxProcessObservationContext(
                record = invocation.record,
                handlerKind = invocation.handlerKind.toObservationHandlerKind(),
                channel = invocation.channel,
            )

        OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS
            .observation(
                resolvedProcessConvention,
                DefaultOutboxProcessObservationConvention.INSTANCE,
                { context },
                observationRegistry,
            ).observe(action)
    }

    /**
     * Maps the Core handler kind to the handler kind used by the observation context.
     *
     * @return The corresponding observation handler kind.
     */
    private fun OutboxProcessHandlerKind.toObservationHandlerKind(): HandlerKind =
        when (this) {
            OutboxProcessHandlerKind.PRIMARY -> HandlerKind.PRIMARY
            OutboxProcessHandlerKind.FALLBACK -> HandlerKind.FALLBACK
        }
}
