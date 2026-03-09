package io.namastack.outbox.observability

import io.micrometer.common.KeyValues
import io.micrometer.common.docs.KeyName
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.docs.ObservationDocumentation

/**
 * Documents the Micrometer observations produced by the outbox library during record processing.
 *
 * Each outbox record is persisted to the database by the producer, then polled and dispatched to
 * its dedicated handler. If the primary handler fails repeatedly (retries exhausted or a
 * non-retryable exception is thrown), the record is handed off to a dedicated fallback handler.
 * An observation is created for every such processing attempt, regardless of whether it is handled
 * by the primary or the fallback handler.
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
enum class OutboxObservationDocumentation : ObservationDocumentation {
    /**
     * Observation that covers the full lifecycle of processing a single polled outbox record —
     * from the moment the handler is invoked until it succeeds, fails, or is handed off to the
     * fallback handler.
     */
    OUTBOX_RECORD_PROCESS {
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOutboxProcessObservationConvention::class.java

        override fun getLowCardinalityKeyNames(): Array<out KeyName> = LowCardinalityKeyNames.entries.toTypedArray()

        override fun getHighCardinalityKeyNames(): Array<out KeyName> = HighCardinalityKeyNames.entries.toTypedArray()
    }, ;

    /**
     * Low-cardinality key names attached to every [OUTBOX_RECORD_PROCESS] observation.
     *
     * Low-cardinality keys are safe to use as metric dimensions because they have a bounded,
     * small set of possible values.
     */
    enum class LowCardinalityKeyNames : KeyName {
        /**
         * Whether the record is being processed by the primary handler or the fallback handler.
         *
         * Possible values: `primary`, `fallback`.
         *
         * @see OutboxProcessObservationContext.HandlerKind
         */
        HANDLER_KIND {
            override fun asString(): String = "outbox.handler.kind"
        },

        /**
         * Unique identifier of the handler (primary or fallback) that is processing the record.
         * Corresponds to the `handlerId` field stored with the outbox record.
         */
        HANDLER_ID {
            override fun asString(): String = "outbox.handler.id"
        },
    }

    /**
     * High-cardinality key names attached to every [OUTBOX_RECORD_PROCESS] observation.
     *
     * High-cardinality keys must not be used as metric dimensions; they are intended for
     * distributed traces and log correlation only.
     */
    enum class HighCardinalityKeyNames : KeyName {
        /**
         * Unique identifier of the outbox record being processed (UUID).
         */
        RECORD_ID {
            override fun asString(): String = "outbox.record.id"
        },

        /**
         * Business key of the outbox record. Used to group or order related records.
         */
        RECORD_KEY {
            override fun asString(): String = "outbox.record.key"
        },

        /**
         * The current delivery attempt number, calculated as `failureCount + 1`.
         * Starts at `1` for a record that has never failed before.
         */
        DELIVERY_ATTEMPT {
            override fun asString(): String = "outbox.delivery.attempt"
        },
    }

    /**
     * Default implementation of [OutboxProcessObservationConvention].
     *
     * Produces the observation name `outbox.record.process` and populates all low- and
     * high-cardinality key values defined in [LowCardinalityKeyNames] and
     * [HighCardinalityKeyNames] from the supplied [OutboxProcessObservationContext].
     */
    class DefaultOutboxProcessObservationConvention : OutboxProcessObservationConvention {
        companion object {
            val INSTANCE = DefaultOutboxProcessObservationConvention()
        }

        override fun getName(): String = "outbox.record.process"

        override fun getContextualName(context: OutboxProcessObservationContext): String = "outbox process"

        override fun getLowCardinalityKeyValues(context: OutboxProcessObservationContext): KeyValues =
            KeyValues.of(
                LowCardinalityKeyNames.HANDLER_KIND.withValue(context.getHandlerKind().toString()),
                LowCardinalityKeyNames.HANDLER_ID.withValue(context.getHandlerId()),
            )

        override fun getHighCardinalityKeyValues(context: OutboxProcessObservationContext): KeyValues =
            KeyValues.of(
                HighCardinalityKeyNames.RECORD_ID.withValue(context.getRecordId()),
                HighCardinalityKeyNames.RECORD_KEY.withValue(context.getRecordKey()),
                HighCardinalityKeyNames.DELIVERY_ATTEMPT.withValue(context.getDeliveryAttempt().toString()),
            )
    }
}
