package io.namastack.demo.customer

import io.namastack.outbox.OutboxEvent
import java.util.UUID

@OutboxEvent(
    key = "#root.id.toString()",
    eventType = "CustomerRegisteredEvent",
)
data class CustomerRegisteredEvent(
    val id: UUID,
    val firstname: String,
    val lastname: String,
    val email: String,
)
