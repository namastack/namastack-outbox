package io.namastack.demo.customer

import io.namastack.outbox.annotation.OutboxEvent
import java.util.UUID

@OutboxEvent(key = "#root.id.toString()")
data class CustomerRegisteredEvent(
    val id: UUID,
    val firstname: String,
    val lastname: String,
    val email: String,
)
