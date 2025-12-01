package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

open class OutboxEventMulticasterTest {
    private var delegateEventMulticaster = mockk<SimpleApplicationEventMulticaster>()
    private var outboxRecordRepository = mockk<OutboxRecordRepository>()
    private var outboxRecordProcessorRegistry = mockk<OutboxRecordProcessorRegistry>()
    private var outboxEventSerializer = mockk<OutboxEventSerializer>()
    private var outboxProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = true))
    private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.of("UTC"))

    private lateinit var eventMulticaster: OutboxEventMulticaster

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        eventMulticaster =
            OutboxEventMulticaster(
                delegateEventMulticaster = delegateEventMulticaster,
                outboxRecordRepository = outboxRecordRepository,
                outboxRecordProcessorRegistry = outboxRecordProcessorRegistry,
                outboxEventSerializer = outboxEventSerializer,
                outboxProperties = outboxProperties,
                clock = clock,
            )

        every { delegateEventMulticaster.multicastEvent(any(), any()) } answers { }
        every { outboxRecordProcessorRegistry.getAllProcessors() } returns
            mapOf(
                Pair(
                    "testProcessor",
                    mockk<OutboxRecordProcessor>(),
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `passes non annotated events to parent`() {
        val event = PayloadApplicationEvent(this, NotAnnotatedTestEvent(id = "agg-1"))

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    fun `passes non PayloadApplicationEvent to parent`() {
        val event = ContextRefreshedEvent(mockk<ApplicationContext>())

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    open fun `stores annotated events in outbox when transaction is active`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))
        val serializedPayload = "serialized"

        every { outboxEventSerializer.serialize(event.payload) } returns serializedPayload
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            outboxRecordRepository.save(
                withArg<OutboxRecord> { record ->
                    assertThat(record.recordKey).isEqualTo("agg-1")
                    assertThat(record.payload).isEqualTo(serializedPayload)
                    assertThat(record.createdAt.toInstant()).isEqualTo(clock.instant())
                    assertThat(
                        record.recordType,
                    ).isEqualTo("io.namastack.outbox.OutboxEventMulticasterTest.AnnotatedTestEvent")
                },
            )
        }
        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(any<PayloadApplicationEvent<*>>(), any())
        }
    }

    @Test
    fun `does not store annotated events in outbox when no transaction is active`() {
        TransactionSynchronizationManager.setActualTransactionActive(false)

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))
        val serializedPayload = "serialized"

        every { outboxEventSerializer.serialize(event.payload) } returns serializedPayload
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("OutboxEvent requires an active transaction")
    }

    @Test
    fun `does not publish to parent when publishAfterSave is false`() {
        val localProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = false))
        val localMulticaster =
            OutboxEventMulticaster(
                delegateEventMulticaster = delegateEventMulticaster,
                outboxRecordRepository = outboxRecordRepository,
                outboxRecordProcessorRegistry = outboxRecordProcessorRegistry,
                outboxEventSerializer = outboxEventSerializer,
                outboxProperties = localProperties,
                clock = clock,
            )

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))

        every { outboxEventSerializer.serialize(event.payload) } returns "serialized"
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        localMulticaster.multicastEvent(event)

        verify(exactly = 0) {
            delegateEventMulticaster.multicastEvent(any(), any())
        }
    }

    @Test
    fun `resolves spel expression for aggregateId`() {
        val event = PayloadApplicationEvent(this, SpELAnnotatedTestEvent(customId = "agg-1"))

        every { outboxEventSerializer.serialize(event.payload) } returns "serialized"
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            outboxRecordRepository.save(
                match { record ->
                    record.recordKey == "agg-1"
                },
            )
        }
    }

    @Test
    fun `throws exception when serialization fails`() {
        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))

        every { outboxEventSerializer.serialize(event.payload) } throws RuntimeException("Serialization failed")

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `throws exception when spel expression returns non string value`() {
        val event = PayloadApplicationEvent(this, NonStringIdEvent(id = 42))
        every { outboxEventSerializer.serialize(any()) } returns "serialized"

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Failed to resolve record key from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
    }

    @Test
    fun `throws exception when spel expression result is null`() {
        val event = PayloadApplicationEvent(this, AnnotatedWithNullableTestEvent(id = null))
        every { outboxEventSerializer.serialize(any()) } returns "serialized"

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Failed to resolve record key from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
    }

    @Test
    fun `uses qualified class name as event type when not specified`() {
        val event = PayloadApplicationEvent(this, DefaultEventTypeTest(id = "agg-1"))
        val serializedPayload = "serialized"

        every { outboxEventSerializer.serialize(event.payload) } returns serializedPayload
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            outboxRecordRepository.save(
                withArg<OutboxRecord> { record ->
                    assertThat(
                        record.recordType,
                    ).isEqualTo("io.namastack.outbox.OutboxEventMulticasterTest.DefaultEventTypeTest")
                },
            )
        }
    }

    @Test
    fun `uses custom event type when specified`() {
        val event = PayloadApplicationEvent(this, CustomEventTypeTest(id = "agg-1"))
        val serializedPayload = "serialized"

        every { outboxEventSerializer.serialize(event.payload) } returns serializedPayload
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            outboxRecordRepository.save(
                withArg<OutboxRecord> { record ->
                    assertThat(record.recordType).isEqualTo("custom.event.type")
                },
            )
        }
    }

    @OutboxEvent(key = "#this.customId")
    data class SpELAnnotatedTestEvent(
        val customId: String,
    )

    @OutboxEvent(key = "id")
    data class AnnotatedTestEvent(
        val id: String,
    )

    @OutboxEvent(key = "id")
    data class AnnotatedWithNullableTestEvent(
        val id: String? = null,
    )

    @OutboxEvent(key = "id")
    data class NonStringIdEvent(
        val id: Int,
    )

    data class NotAnnotatedTestEvent(
        val id: String,
    )

    @OutboxEvent(key = "id")
    data class DefaultEventTypeTest(
        val id: String,
    )

    @OutboxEvent(key = "id", eventType = "custom.event.type")
    data class CustomEventTypeTest(
        val id: String,
    )
}
