package io.namastack.demo.customer

import io.namastack.demo.DomainEvent

data class CustomerActivatedEvent(
    override val id: String,
) : DomainEvent
