package io.namastack.outbox.observability

import io.micrometer.common.KeyValues
import io.micrometer.common.docs.KeyName
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.docs.ObservationDocumentation

/**
 * Documents the Micrometer observations produced by the outbox library.
 *
 * Three observations cover the outbox lifecycle:
 * - [OUTBOX_RECORD_ATTEMPT]: Processing one fully materialized record
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
     * Observation that covers one complete attempt to process a fully materialized record.
     */
    OUTBOX_RECORD_ATTEMPT {
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOutboxRecordProcessingObservationConvention::class.java

        override fun getLowCardinalityKeyNames(): Array<out KeyName> =
            AttemptLowCardinalityKeyNames.entries.toTypedArray()

        override fun getHighCardinalityKeyNames(): Array<out KeyName> = HighCardinalityKeyNames.entries.toTypedArray()
    },

    /**
     * Observation that covers one primary or fallback handler invocation.
     */
    OUTBOX_RECORD_PROCESS {
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOutboxHandlerObservationConvention::class.java

        override fun getLowCardinalityKeyNames(): Array<out KeyName> = LowCardinalityKeyNames.entries.toTypedArray()

        override fun getHighCardinalityKeyNames(): Array<out KeyName> = HighCardinalityKeyNames.entries.toTypedArray()
    },

    /**
     * Observation covering the scheduling of outbox records within a transaction.
     * Starts when `schedule()` is called and stops after all records are persisted.
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
         * @see OutboxHandlerObservationContext.HandlerKind
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
         */
        CHANNEL {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.CHANNEL
        },
    }

    /**
     * Low-cardinality key names attached to every [OUTBOX_RECORD_ATTEMPT] observation.
     */
    enum class AttemptLowCardinalityKeyNames : KeyName {
        /**
         * Final outcome of the processing attempt.
         *
         * Possible values: `completed`, `retry_scheduled`, `failed`, `compatibility_deferred`, or `error`.
         *
         * @see OutboxRecordProcessingObservationContext.Outcome
         */
        OUTCOME {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.PROCESSING_OUTCOME
        },

        /**
         * Logical channel name of the outbox runtime processing this record.
         */
        CHANNEL {
            override fun asString(): String = OutboxMetricKeyNames.LowCardinality.CHANNEL
        },
    }

    /**
     * High-cardinality key names attached to record-attempt and handler observations.
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
     * Default implementation of [OutboxHandlerObservationConvention].
     *
     * Produces the established observation name `outbox.record.process` for one primary or
     * fallback handler invocation and populates all key values from the supplied context.
     */
    class DefaultOutboxHandlerObservationConvention : OutboxHandlerObservationConvention {
        companion object {
            val INSTANCE = DefaultOutboxHandlerObservationConvention()
        }

        override fun getName(): String = OutboxMetricNames.RECORD_PROCESS

        override fun getContextualName(context: OutboxHandlerObservationContext): String = "outbox process"

        override fun getLowCardinalityKeyValues(context: OutboxHandlerObservationContext): KeyValues =
            KeyValues.of(
                LowCardinalityKeyNames.HANDLER_KIND.withValue(context.getHandlerKind().toString()),
                LowCardinalityKeyNames.HANDLER_ID.withValue(context.getHandlerId()),
                LowCardinalityKeyNames.CHANNEL.withValue(context.getChannel()),
            )

        override fun getHighCardinalityKeyValues(context: OutboxHandlerObservationContext): KeyValues =
            KeyValues.of(
                HighCardinalityKeyNames.RECORD_ID.withValue(context.getRecordId()),
                HighCardinalityKeyNames.RECORD_KEY.withValue(context.getRecordKey()),
                HighCardinalityKeyNames.DELIVERY_ATTEMPT.withValue(context.getDeliveryAttempt().toString()),
            )
    }

    /**
     * Default implementation of [OutboxRecordProcessingObservationConvention].
     *
     * Produces the observation name `outbox.record.attempt`. The final
     * [OutboxRecordProcessingObservationContext.Outcome] is added by the instrumentation when
     * processing completes.
     */
    class DefaultOutboxRecordProcessingObservationConvention : OutboxRecordProcessingObservationConvention {
        companion object {
            val INSTANCE = DefaultOutboxRecordProcessingObservationConvention()
        }

        override fun getName(): String = OutboxMetricNames.RECORD_ATTEMPT

        override fun getContextualName(context: OutboxRecordProcessingObservationContext): String =
            "outbox record attempt"

        override fun getLowCardinalityKeyValues(context: OutboxRecordProcessingObservationContext): KeyValues =
            KeyValues.of(
                AttemptLowCardinalityKeyNames.CHANNEL.withValue(context.getChannel()),
            )

        override fun getHighCardinalityKeyValues(context: OutboxRecordProcessingObservationContext): KeyValues =
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
