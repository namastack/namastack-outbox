package io.namastack.demo.customer

import io.namastack.outbox.annotation.OutboxEvent

@OutboxEvent(key = "#root.id")
data class CustomerRegisteredEvent(
    val id: String,
    val firstname: String,
    val lastname: String,
    val email: String,
)
