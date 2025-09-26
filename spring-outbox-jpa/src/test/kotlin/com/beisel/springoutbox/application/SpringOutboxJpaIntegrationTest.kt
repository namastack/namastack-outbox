package com.beisel.springoutbox.application

import com.beisel.springoutbox.OutboxJpaTest
import com.beisel.springoutbox.OutboxRecord
import com.beisel.springoutbox.OutboxRecordRepository
import com.beisel.springoutbox.application.event.DomainEvent
import com.beisel.springoutbox.application.event.OrderCanceledEvent
import com.beisel.springoutbox.application.event.OrderCreatedEvent
import com.beisel.springoutbox.application.event.OrderUpdatedEvent
import com.beisel.springoutbox.lock.OutboxLock
import com.beisel.springoutbox.lock.OutboxLockRepository
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@OutboxJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(DemoProcessor::class)
class SpringOutboxJpaIntegrationTest {
    @Autowired
    private lateinit var outboxRepository: OutboxRecordRepository

    @Autowired
    private lateinit var lockRepository: OutboxLockRepository

    private val objectMapper: ObjectMapper = ObjectMapper()

    @Test
    fun contextLoads() {
        val orderId1 = UUID.randomUUID()
        val orderId2 = UUID.randomUUID()

        createOutboxEntry(OrderCreatedEvent(orderId1))
        createOutboxEntry(OrderCreatedEvent(orderId2))
        createOutboxEntry(OrderUpdatedEvent(orderId2))
        createOutboxEntry(OrderUpdatedEvent(orderId1))
        createOutboxEntry(OrderCanceledEvent(orderId1))
        createOutboxEntry(OrderCanceledEvent(orderId2))

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until {
            outboxRepository.findPendingRecords().isEmpty()
        }
    }

    @Test
    fun testOvertake() {
        val aggregateId = UUID.randomUUID()

        val lock =
            OutboxLock(
                aggregateId = aggregateId.toString(),
                acquiredAt = OffsetDateTime.now().minus(1, ChronoUnit.MINUTES),
                expiresAt = OffsetDateTime.now().minus(1, ChronoUnit.MINUTES),
                version = null,
            )

        lockRepository.insertNew(lock)

        createOutboxEntry(OrderCreatedEvent(aggregateId))

        Awaitility.await().atMost(20, TimeUnit.SECONDS).until {
            outboxRepository.findPendingRecords().isEmpty()
        }
    }

    private fun createOutboxEntry(event: DomainEvent) {
        val entry =
            OutboxRecord
                .Builder()
                .id(event.eventId.toString())
                .aggregateId(event.aggregateId.toString())
                .eventType(event::class.simpleName ?: "UnknownEvent")
                .payload(serialize(event))
                .build()

        Thread.sleep(200)
        outboxRepository.save(entry)
    }

    private fun serialize(event: DomainEvent): String = objectMapper.writeValueAsString(event)
}
