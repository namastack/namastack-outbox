package io.namastack.demo.customer

import io.namastack.demo.DomainEvent
import io.namastack.outbox.OutboxEvent

@OutboxEvent(aggregateId = "id")
data class CustomerDeactivatedEvent(
    override val id: String,
) : DomainEvent
