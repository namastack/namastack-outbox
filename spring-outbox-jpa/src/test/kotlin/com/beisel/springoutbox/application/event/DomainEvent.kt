package com.beisel.springoutbox.application.event

import java.util.UUID

sealed class DomainEvent(
    open val aggregateId: UUID,
) {
    val eventId: UUID = UUID.randomUUID()
}
