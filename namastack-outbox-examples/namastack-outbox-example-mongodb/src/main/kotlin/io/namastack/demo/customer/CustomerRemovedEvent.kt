package io.namastack.demo.customer

import io.namastack.outbox.annotation.OutboxEvent

@OutboxEvent(key = "#root.id")
data class CustomerRemovedEvent(
    val id: String,
)
