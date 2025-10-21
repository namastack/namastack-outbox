package io.namastack.demo.customer

import io.namastack.demo.DomainEvent

data class CustomerDeactivatedEvent(
    override val id: String,
) : DomainEvent
