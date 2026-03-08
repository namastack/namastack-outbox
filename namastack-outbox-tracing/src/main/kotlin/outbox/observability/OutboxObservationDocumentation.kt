package io.namastack.outbox.observability

import io.micrometer.common.KeyValues
import io.micrometer.common.docs.KeyName
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.docs.ObservationDocumentation

enum class OutboxObservationDocumentation : ObservationDocumentation {
    OUTBOX_RECORD_PROCESS {
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOutboxProcessObservationConvention::class.java

        override fun getLowCardinalityKeyNames(): Array<out KeyName> = LowCardinalityKeyNames.entries.toTypedArray()

        override fun getHighCardinalityKeyNames(): Array<out KeyName> = HighCardinalityKeyNames.entries.toTypedArray()
    }, ;

    enum class LowCardinalityKeyNames : KeyName {
        HANDLER_KIND {
            override fun asString(): String = "outbox.handler.kind"
        },

        HANDLER_ID {
            override fun asString(): String = "outbox.handler.id"
        },
    }

    enum class HighCardinalityKeyNames : KeyName {
        RECORD_ID {
            override fun asString(): String = "outbox.record.id"
        },

        RECORD_KEY {
            override fun asString(): String = "outbox.record.key"
        },

        DELIVERY_ATTEMPT {
            override fun asString(): String = "outbox.delivery.attempt"
        },
    }

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
