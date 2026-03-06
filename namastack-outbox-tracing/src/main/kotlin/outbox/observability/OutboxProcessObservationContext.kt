package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext

class OutboxProcessObservationContext(
    carrier: OutboxProcessObservationContextCarrier,
) : ReceiverContext<OutboxProcessObservationContextCarrier>(
        { carrier: OutboxProcessObservationContextCarrier, key: String ->
            carrier.metadata.context[key]
        },
    ) {
    init {
        setCarrier(carrier)
    }
}
