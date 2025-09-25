package com.beisel.springoutbox.application.event

import java.util.UUID

data class OrderCreatedEvent(
    override val aggregateId: UUID,
) : DomainEvent(aggregateId)
