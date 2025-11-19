package io.namastack.demo.customer

import io.namastack.outbox.OutboxEvent
import java.util.UUID

@OutboxEvent(aggregateId = "#root.id.toString()")
data class CustomerDeactivatedEvent(
    val id: UUID,
)
