package com.beisel.springoutbox.application.event

import java.util.UUID

data class OrderUpdatedEvent(
    override val aggregateId: UUID,
) : DomainEvent(aggregateId)
