package io.namastack.outbox.observability

import io.namastack.outbox.handler.OutboxRecordMetadata

data class OutboxProcessObservationContextCarrier(
    val payload: Any,
    val metadata: OutboxRecordMetadata,
)
