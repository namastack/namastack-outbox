package io.namastack.demo.customer

import io.namastack.outbox.OutboxEvent
import java.util.UUID

@OutboxEvent(aggregateId = "#root.id.toString()")
data class CustomerRegisteredEvent(
    val id: UUID,
    val firstname: String,
    val lastname: String,
    val email: String,
)
