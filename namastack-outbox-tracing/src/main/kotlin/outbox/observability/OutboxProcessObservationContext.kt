package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxRecord

class OutboxProcessObservationContext(
    private val record: OutboxRecord<*>,
    private val handlerType: HandlerType,
) : ReceiverContext<OutboxRecord<*>>({ carrier: OutboxRecord<*>, key: String -> carrier.context[key] }) {
    init {
        setCarrier(record)
    }

    fun getHandlerType(): HandlerType = handlerType

    fun getHandlerId(): String = record.handlerId

    fun getRecordId(): String = record.id

    fun getRecordKey(): String = record.key

    fun getDeliveryAttempt(): Int = record.failureCount + 1

    enum class HandlerType(
        val value: String,
    ) {
        HANDLER("handler"),
        FALLBACK("fallback"),
        ;

        override fun toString(): String = value
    }
}
