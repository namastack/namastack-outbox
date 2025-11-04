package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.SimpleApplicationEventMulticaster
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class OutboxEventMulticasterTest {
    private var beanFactory = mockk<ConfigurableBeanFactory>()
    private var baseMulticaster = mockk<SimpleApplicationEventMulticaster>()
    private var outboxRecordRepository = mockk<OutboxRecordRepository>()
    private var outboxEventSerializer = mockk<OutboxEventSerializer>()
    private var outboxProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = true))
    private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.of("UTC"))

    private lateinit var eventMulticaster: OutboxEventMulticaster

    @BeforeEach
    fun setUp() {
        every { beanFactory.beanClassLoader } returns this::class.java.classLoader

        eventMulticaster =
            OutboxEventMulticaster(
                beanFactory = beanFactory,
                baseMulticaster = baseMulticaster,
                outboxRecordRepository = outboxRecordRepository,
                outboxEventSerializer = outboxEventSerializer,
                outboxProperties = outboxProperties,
                clock = clock,
            )

        every { baseMulticaster.multicastEvent(any(), any()) } answers { }
    }

    @Test
    fun `passes non annotated events to parent`() {
        val event = PayloadApplicationEvent(this, NotAnnotatedTestEvent(id = "agg-1"))

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            baseMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    fun `passes non PayloadApplicationEvent to parent`() {
        val event = ContextRefreshedEvent(mockk<ApplicationContext>())

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            baseMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    fun `stores annotated events in outbox`() {
        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))
        val serializedPayload = "serialized"

        every { outboxEventSerializer.serialize(event.payload) } returns serializedPayload
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            outboxRecordRepository.save(
                withArg<OutboxRecord> { record ->
                    assertThat(record.aggregateId).isEqualTo("agg-1")
                    assertThat(record.payload).isEqualTo(serializedPayload)
                    assertThat(record.eventType).isEqualTo("AnnotatedTestEvent")
                    assertThat(record.createdAt.toInstant()).isEqualTo(clock.instant())
                },
            )
        }
        verify(exactly = 1) {
            baseMulticaster.multicastEvent(any<PayloadApplicationEvent<*>>(), any())
        }
    }

    @Test
    fun `does not publish to parent when publishAfterSave is false`() {
        val localProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = false))
        val localMulticaster =
            OutboxEventMulticaster(
                beanFactory = beanFactory,
                baseMulticaster = baseMulticaster,
                outboxRecordRepository = outboxRecordRepository,
                outboxEventSerializer = outboxEventSerializer,
                outboxProperties = localProperties,
                clock = clock,
            )

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))

        every { outboxEventSerializer.serialize(event.payload) } returns "serialized"
        every { outboxRecordRepository.save(any()) } answers { firstArg() }

        localMulticaster.multicastEvent(event)

        verify(exactly = 0) {
            baseMulticaster.multicastEvent(any(), any())
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
                    record.aggregateId == "agg-1"
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
            .hasMessage("Failed to resolve aggregateId from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
    }

    @Test
    fun `throws exception when spel expression result is null`() {
        val event = PayloadApplicationEvent(this, AnnotatedWithNullableTestEvent(id = null))
        every { outboxEventSerializer.serialize(any()) } returns "serialized"

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Failed to resolve aggregateId from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
    }

    @OutboxEvent(aggregateId = "#this.customId")
    data class SpELAnnotatedTestEvent(
        val customId: String,
    )

    @OutboxEvent(aggregateId = "id")
    data class AnnotatedTestEvent(
        val id: String,
    )

    @OutboxEvent(aggregateId = "id")
    data class AnnotatedWithNullableTestEvent(
        val id: String? = null,
    )

    @OutboxEvent(aggregateId = "id")
    data class NonStringIdEvent(
        val id: Int,
    )

    data class NotAnnotatedTestEvent(
        val id: String,
    )
}
