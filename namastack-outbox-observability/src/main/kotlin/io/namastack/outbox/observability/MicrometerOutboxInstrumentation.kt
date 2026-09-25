package io.namastack.outbox.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxHandlerKind
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.observability.OutboxHandlerObservationContext.HandlerKind
import io.namastack.outbox.observability.OutboxObservationDocumentation.AttemptLowCardinalityKeyNames
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxHandlerObservationConvention
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxRecordProcessingObservationConvention
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxScheduleObservationConvention
import io.namastack.outbox.observability.OutboxRecordProcessingObservationContext.Outcome
import java.util.function.Supplier

/**
 * Instruments outbox scheduling, record processing, and handler invocation with Micrometer.
 *
 * The record-processing observation restores persisted propagation context. Primary and fallback
 * handler observations execute as its children.
 *
 * @param observationRegistrySupplier Lazy supplier for the registry used to create observations.
 * @param customScheduleConventionSupplier Lazy supplier for an optional custom scheduling observation convention.
 * @param customRecordProcessingConventionSupplier Lazy supplier for an optional record-processing convention.
 * @param customHandlerConventionSupplier Lazy supplier for an optional handler observation convention.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class MicrometerOutboxInstrumentation(
    observationRegistrySupplier: () -> ObservationRegistry,
    customScheduleConventionSupplier: () -> OutboxScheduleObservationConvention? = { null },
    customRecordProcessingConventionSupplier: () -> OutboxRecordProcessingObservationConvention? = { null },
    customHandlerConventionSupplier: () -> OutboxHandlerObservationConvention? = { null },
) : OutboxInstrumentation {
    private val observationRegistry: ObservationRegistry by lazy {
        observationRegistrySupplier()
    }
    private val resolvedScheduleConvention: OutboxScheduleObservationConvention? by lazy {
        customScheduleConventionSupplier()
    }
    private val resolvedRecordProcessingConvention: OutboxRecordProcessingObservationConvention? by lazy {
        customRecordProcessingConventionSupplier()
    }
    private val resolvedHandlerConvention: OutboxHandlerObservationConvention? by lazy {
        customHandlerConventionSupplier()
    }

    /**
     * Creates instrumentation with an already resolved registry.
     *
     * @param observationRegistry Registry used to create observations.
     * @param customScheduleConventionSupplier Lazy supplier for an optional custom scheduling observation convention.
     * @param customRecordProcessingConventionSupplier Lazy supplier for an optional record-processing convention.
     * @param customHandlerConventionSupplier Lazy supplier for an optional handler observation convention.
     */
    constructor(
        observationRegistry: ObservationRegistry,
        customScheduleConventionSupplier: () -> OutboxScheduleObservationConvention? = { null },
        customRecordProcessingConventionSupplier: () -> OutboxRecordProcessingObservationConvention? = { null },
        customHandlerConventionSupplier: () -> OutboxHandlerObservationConvention? = { null },
    ) : this(
        observationRegistrySupplier = { observationRegistry },
        customScheduleConventionSupplier = customScheduleConventionSupplier,
        customRecordProcessingConventionSupplier = customRecordProcessingConventionSupplier,
        customHandlerConventionSupplier = customHandlerConventionSupplier,
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
     * Observes one complete record-processing attempt.
     *
     * @param invocation Description used to create the record-processing observation context.
     * @param action Processor-chain action executed within the observation scope.
     * @return The unchanged processing outcome returned by [action].
     */
    override fun processRecord(
        invocation: OutboxRecordProcessingInvocation,
        action: () -> OutboxRecordProcessingOutcome,
    ): OutboxRecordProcessingOutcome {
        val context =
            OutboxRecordProcessingObservationContext(
                record = invocation.record,
                channel = invocation.channel,
            )
        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_ATTEMPT
                .observation(
                    resolvedRecordProcessingConvention,
                    DefaultOutboxRecordProcessingObservationConvention.INSTANCE,
                    { context },
                    observationRegistry,
                )

        return observation.observe(
            Supplier {
                try {
                    action().also { outcome ->
                        completeAttempt(observation, context, outcome.toObservationOutcome())
                    }
                } catch (ex: Throwable) {
                    completeAttempt(observation, context, classifyException(ex))
                    throw ex
                }
            },
        )
    }

    /**
     * Observes one primary or fallback handler invocation.
     *
     * @param invocation Description used to create the handler observation context.
     * @param action Handler action executed within the observation scope.
     */
    override fun invokeHandler(
        invocation: OutboxHandlerInvocation,
        action: () -> Unit,
    ) {
        val context =
            OutboxHandlerObservationContext(
                record = invocation.record,
                handlerKind = invocation.handlerKind.toObservationHandlerKind(),
                channel = invocation.channel,
            )

        OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS
            .observation(
                resolvedHandlerConvention,
                DefaultOutboxHandlerObservationConvention.INSTANCE,
                { context },
                observationRegistry,
            ).observe(action)
    }

    private fun completeAttempt(
        observation: Observation,
        context: OutboxRecordProcessingObservationContext,
        outcome: Outcome,
    ) {
        context.setOutcome(outcome)
        observation.lowCardinalityKeyValue(
            KeyValue.of(
                AttemptLowCardinalityKeyNames.OUTCOME.asString(),
                outcome.value,
            ),
        )
    }

    private fun OutboxRecordProcessingOutcome.toObservationOutcome(): Outcome =
        when (this) {
            OutboxRecordProcessingOutcome.COMPLETED -> Outcome.COMPLETED
            OutboxRecordProcessingOutcome.RETRY_SCHEDULED -> Outcome.RETRY_SCHEDULED
            OutboxRecordProcessingOutcome.FAILED -> Outcome.FAILED
        }

    private fun classifyException(ex: Throwable): Outcome =
        when (ex) {
            is OutboxHandlerNotFoundException -> Outcome.COMPATIBILITY_DEFERRED
            else -> Outcome.ERROR
        }

    private fun OutboxHandlerKind.toObservationHandlerKind(): HandlerKind =
        when (this) {
            OutboxHandlerKind.PRIMARY -> HandlerKind.PRIMARY
            OutboxHandlerKind.FALLBACK -> HandlerKind.FALLBACK
        }
}
