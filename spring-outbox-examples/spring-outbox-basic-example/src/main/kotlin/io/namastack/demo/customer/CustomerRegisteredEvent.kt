package io.namastack.demo.customer

import io.namastack.demo.DomainEvent

data class CustomerRegisteredEvent(
    override val id: String,
    val firstname: String,
    val lastname: String,
) : DomainEvent
