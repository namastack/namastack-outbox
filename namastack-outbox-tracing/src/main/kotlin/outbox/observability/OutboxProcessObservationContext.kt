package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxRecord

class OutboxProcessObservationContext(
    private val carrier: OutboxRecord<*>,
    private val operation: String,
) : ReceiverContext<OutboxRecord<*>>(
        { carrier: OutboxRecord<*>, key: String ->
            carrier.context[key]
        },
    ) {
    init {
        setCarrier(carrier)
    }

    fun getKey(): String = carrier.key

    fun getHandlerId(): String = carrier.handlerId

    fun getDeliveryAttempt(): Int = carrier.failureCount + 1

    fun getOperation(): String = operation
}
