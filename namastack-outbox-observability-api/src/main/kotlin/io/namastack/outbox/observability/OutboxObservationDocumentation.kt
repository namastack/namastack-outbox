package io.namastack.outbox.observability

import io.micrometer.common.KeyValues
import io.micrometer.common.docs.KeyName
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.docs.ObservationDocumentation

/**
 * Documents the Micrometer observations produced by the outbox library.
 *
 * Two observations cover the outbox lifecycle:
 * - [OUTBOX_RECORD_PROCESS]: Dispatching a record to its handler (primary or fallback)
 * - [OUTBOX_RECORD_SCHEDULE]: Scheduling one or more records into the outbox
 *
 * Each observation produces both distributed trace spans and timer metrics automatically
 * when the appropriate Micrometer handlers are registered (Spring Boot default behavior).
 *
 * @author Aleksander Zamojski, Roland Beisel
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
    },

    /**
     * Observation covering the scheduling of outbox records within a transaction.
     * Starts when `schedule()` is called and stops after all records are persisted.
     *
     * @since 1.3.0
     */
    OUTBOX_RECORD_SCHEDULE {
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOutboxScheduleObservationConvention::class.java

        override fun getLowCardinalityKeyNames(): Array<out KeyName> =
            ScheduleLowCardinalityKeyNames.entries.toTypedArray()

        override fun getHighCardinalityKeyNames(): Array<out KeyName> =
            ScheduleHighCardinalityKeyNames.entries.toTypedArray()
    },
    ;

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
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.HANDLER_KIND
        },

        /**
         * Unique identifier of the handler (primary or fallback) that is processing the record.
         * Corresponds to the `handlerId` field stored with the outbox record.
         */
        HANDLER_ID {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.HANDLER_ID
        },

        /**
         * Logical channel name of the outbox runtime processing this record.
         * In OSS mode this is `"default"`, in Pro multi-channel mode it is the channel id.
         *
         * @since 1.3.0
         */
        CHANNEL {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.CHANNEL
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
            override fun asString(): String = OutboxMetricKeyNames.HighCardinality.RECORD_ID
        },

        /**
         * Business key of the outbox record. Used to group or order related records.
         */
        RECORD_KEY {
            override fun asString(): String = OutboxMetricKeyNames.HighCardinality.RECORD_KEY
        },

        /**
         * The current delivery attempt number, calculated as `failureCount + 1`.
         * Starts at `1` for a record that has never failed before.
         */
        DELIVERY_ATTEMPT {
            override fun asString(): String = OutboxMetricKeyNames.HighCardinality.DELIVERY_ATTEMPT
        },
    }

    /**
     * Low-cardinality key names for [OUTBOX_RECORD_SCHEDULE].
     *
     * @since 1.3.0
     */
    enum class ScheduleLowCardinalityKeyNames : KeyName {
        /**
         * Logical channel name of the outbox runtime.
         */
        CHANNEL {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.CHANNEL
        },
    }

    /**
     * High-cardinality key names for [OUTBOX_RECORD_SCHEDULE].
     *
     * @since 1.3.0
     */
    enum class ScheduleHighCardinalityKeyNames : KeyName {
        /**
         * The record key used for partitioning and ordering.
         */
        RECORD_KEY {
            override fun asString(): String = OutboxMetricKeyNames.HighCardinality.SCHEDULE_RECORD_KEY
        },

        /**
         * Simple class name of the payload being scheduled.
         */
        PAYLOAD_TYPE {
            override fun asString(): String = OutboxMetricKeyNames.HighCardinality.SCHEDULE_PAYLOAD_TYPE
        },
    }

    /**
     * Default implementation of [OutboxProcessObservationConvention].
     *
     * Produces the observation name `outbox.record.process` and populates all low- and
     * high-cardinality key values from the supplied [OutboxProcessObservationContext].
     */
    class DefaultOutboxProcessObservationConvention : OutboxProcessObservationConvention {
        companion object {
            val INSTANCE = DefaultOutboxProcessObservationConvention()
        }

        override fun getName(): String = OutboxMetricNames.RECORD_PROCESS

        override fun getContextualName(context: OutboxProcessObservationContext): String = "outbox process"

        override fun getLowCardinalityKeyValues(context: OutboxProcessObservationContext): KeyValues =
            KeyValues.of(
                LowCardinalityKeyNames.HANDLER_KIND.withValue(context.getHandlerKind().toString()),
                LowCardinalityKeyNames.HANDLER_ID.withValue(context.getHandlerId()),
                LowCardinalityKeyNames.CHANNEL.withValue(context.getChannel()),
            )

        override fun getHighCardinalityKeyValues(context: OutboxProcessObservationContext): KeyValues =
            KeyValues.of(
                HighCardinalityKeyNames.RECORD_ID.withValue(context.getRecordId()),
                HighCardinalityKeyNames.RECORD_KEY.withValue(context.getRecordKey()),
                HighCardinalityKeyNames.DELIVERY_ATTEMPT.withValue(context.getDeliveryAttempt().toString()),
            )
    }

    /**
     * Default implementation of [OutboxScheduleObservationConvention].
     *
     * Produces the observation name `outbox.record.schedule`.
     *
     * @since 1.3.0
     */
    class DefaultOutboxScheduleObservationConvention : OutboxScheduleObservationConvention {
        companion object {
            val INSTANCE = DefaultOutboxScheduleObservationConvention()
        }

        override fun getName(): String = OutboxMetricNames.RECORD_SCHEDULE

        override fun getContextualName(context: OutboxScheduleObservationContext): String = "outbox schedule"

        override fun getLowCardinalityKeyValues(context: OutboxScheduleObservationContext): KeyValues =
            KeyValues.of(
                ScheduleLowCardinalityKeyNames.CHANNEL.withValue(context.channel),
            )

        override fun getHighCardinalityKeyValues(context: OutboxScheduleObservationContext): KeyValues =
            KeyValues.of(
                ScheduleHighCardinalityKeyNames.RECORD_KEY.withValue(context.recordKey),
                ScheduleHighCardinalityKeyNames.PAYLOAD_TYPE.withValue(context.payloadType),
            )
    }
}
