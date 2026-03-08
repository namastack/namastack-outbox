package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxRecord

class OutboxProcessObservationContext(
    private val record: OutboxRecord<*>,
    private val handlerKind: HandlerKind,
) : ReceiverContext<OutboxRecord<*>>({ carrier: OutboxRecord<*>, key: String -> carrier.context[key] }) {
    init {
        setCarrier(record)
    }

    fun getHandlerKind(): HandlerKind = handlerKind

    fun getHandlerId(): String = record.handlerId

    fun getRecordId(): String = record.id

    fun getRecordKey(): String = record.key

    fun getDeliveryAttempt(): Int = record.failureCount + 1

    enum class HandlerKind(
        val value: String,
    ) {
        PRIMARY("primary"),
        FALLBACK("fallback"),
        ;

        override fun toString(): String = value
    }
}
