package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxRecord

/**
 * Micrometer [ReceiverContext] that carries all information needed to instrument the processing
 * of a single outbox record.
 *
 * An instance of this context is created just before the handler (primary or fallback) is invoked
 * for a polled outbox record. The carrier is the [OutboxRecord] itself, so propagation headers
 * stored in [OutboxRecord.context] are automatically made available to Micrometer's propagation
 * mechanism (e.g. for distributed tracing).
 *
 * @param record The outbox record that is about to be processed.
 * @param handlerKind Whether this processing attempt uses the primary or the fallback handler.
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
class OutboxProcessObservationContext(
    private val record: OutboxRecord<*>,
    private val handlerKind: HandlerKind,
) : ReceiverContext<OutboxRecord<*>>({ carrier: OutboxRecord<*>, key: String -> carrier.context[key] }) {
    init {
        setCarrier(record)
    }

    /**
     * Returns whether the current processing attempt is performed by the primary or the fallback
     * handler.
     */
    fun getHandlerKind(): HandlerKind = handlerKind

    /**
     * Returns the unique identifier of the handler that is processing this record.
     * Matches the `handlerId` field persisted with the outbox record.
     */
    fun getHandlerId(): String = record.handlerId

    /**
     * Returns the unique identifier (UUID) of the outbox record being processed.
     */
    fun getRecordId(): String = record.id

    /**
     * Returns the business key of the outbox record. Related records share the same key and are
     * processed in order within the same partition.
     */
    fun getRecordKey(): String = record.key

    /**
     * Returns the current delivery attempt number, calculated as `failureCount + 1`.
     * The value is `1` the first time a record is processed and increases with every failed
     * attempt.
     */
    fun getDeliveryAttempt(): Int = record.failureCount + 1

    /**
     * Indicates which type of handler is processing the outbox record.
     *
     * @property value String representation used as the observation key value.
     */
    enum class HandlerKind(
        val value: String,
    ) {
        /**
         * The primary handler, which is the first handler invoked for every polled record.
         */
        PRIMARY("primary"),

        /**
         * The fallback handler, which is invoked when the primary handler has exhausted its
         * retries or thrown a non-retryable exception.
         */
        FALLBACK("fallback"),
        ;

        override fun toString(): String = value
    }
}
