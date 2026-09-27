package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord

/**
 * Micrometer context for one primary or fallback handler invocation.
 *
 * The enclosing [OutboxRecordProcessingObservationContext] restores propagation headers from the
 * record. Handler observations therefore use the active record-processing observation as parent.
 *
 * @param record The outbox record passed to the handler.
 * @param handlerKind Whether the primary or fallback handler is being invoked.
 * @param channel The logical channel name (defaults to `"default"` in OSS mode).
 *
 * @author Aleksander Zamojski, Roland Beisel
 * @since 1.2.0
 */
class OutboxProcessObservationContext(
    private val record: OutboxRecord<*>,
    private val handlerKind: HandlerKind,
    private val channel: String = OutboxChannelNameProvider.DEFAULT_CHANNEL,
) : Observation.Context() {
    private val deliveryAttempt: Int =
        when (handlerKind) {
            HandlerKind.PRIMARY -> record.failureCount + 1
            HandlerKind.FALLBACK -> record.failureCount
        }

    /** Returns whether the primary or fallback handler is being invoked. */
    fun getHandlerKind(): HandlerKind = handlerKind

    /** Returns the identifier of the handler being invoked. */
    fun getHandlerId(): String = record.handlerId

    /** Returns the unique identifier of the outbox record. */
    fun getRecordId(): String = record.id

    /** Returns the business key used to order the outbox record. */
    fun getRecordKey(): String = record.key

    /**
     * Returns the delivery attempt snapshotted when this handler context was created.
     * Primary handlers use `failureCount + 1`. Fallback handlers use `failureCount` because the
     * primary processor has already incremented it before fallback dispatch.
     */
    fun getDeliveryAttempt(): Int = deliveryAttempt

    /** Returns the logical channel name of the outbox runtime. */
    fun getChannel(): String = channel

    /**
     * Indicates which handler is being invoked.
     *
     * @property value String representation used as the observation key value.
     */
    enum class HandlerKind(
        val value: String,
    ) {
        /** The primary handler invoked first for an outbox record. */
        PRIMARY("primary"),

        /** The fallback handler invoked after primary delivery cannot complete. */
        FALLBACK("fallback"),
        ;

        override fun toString(): String = value
    }
}
