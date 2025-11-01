package io.namastack.demo.customer

import io.namastack.demo.DomainEvent
import io.namastack.outbox.OutboxEvent

@OutboxEvent(aggregateId = "id")
data class CustomerRegisteredEvent(
    override val id: String,
    val firstname: String,
    val lastname: String,
) : DomainEvent
