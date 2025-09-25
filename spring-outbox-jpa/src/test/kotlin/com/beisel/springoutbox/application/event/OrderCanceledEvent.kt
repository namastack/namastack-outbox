package com.beisel.springoutbox.application.event

import java.util.UUID

data class OrderCanceledEvent(
    override val aggregateId: UUID,
) : DomainEvent(aggregateId)
