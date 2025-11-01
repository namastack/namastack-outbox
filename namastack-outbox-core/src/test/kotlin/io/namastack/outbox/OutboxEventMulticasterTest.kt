package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.PayloadApplicationEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DisplayName("OutboxEventMulticaster")
class OutboxEventMulticasterTest {
    private val clock = Clock.fixed(Instant.parse("2025-10-25T10:00:00Z"), ZoneOffset.UTC)

    private val beanFactory = mockk<ConfigurableBeanFactory>()
    private val recordRepository = mockk<OutboxRecordRepository>()
    private val eventSerializer = mockk<OutboxEventSerializer>()
    private val properties =
        OutboxProperties(
            processing = OutboxProperties.Processing(publishAfterSave = true),
        )

    private lateinit var multicaster: OutboxEventMulticaster

    @BeforeEach
    fun setUp() {
        every { beanFactory.getBeanClassLoader() } returns OutboxEventMulticasterTest::class.java.classLoader
        every { beanFactory.resolveEmbeddedValue(any()) } answers { firstArg() }

        multicaster = OutboxEventMulticaster(beanFactory, recordRepository, eventSerializer, properties, clock)

        every { recordRepository.save(any()) } returns mockk()
        every { eventSerializer.serialize(any()) } returns "serialized-payload"
    }

    @Nested
    @DisplayName("Event Publishing")
    inner class EventPublishing {
        @Test
        fun `saves non-outbox event to outbox when marked with @OutboxEvent`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test-data")
            val event = PayloadApplicationEvent(this, payload)
            val recordSlot = slot<OutboxRecord>()

            every { recordRepository.save(capture(recordSlot)) } returns mockk()

            multicaster.multicastEvent(event)

            verify(exactly = 1) { recordRepository.save(any()) }
            val captured = recordSlot.captured
            assertThat(captured.aggregateId).isEqualTo("agg-1")
            assertThat(captured.eventType).isEqualTo("TestOutboxEvent")
            assertThat(captured.payload).isEqualTo("serialized-payload")
        }

        @Test
        fun `ignores events without @OutboxEvent annotation`() {
            val payload = TestRegularEvent(data = "test-data")
            val event = PayloadApplicationEvent(this, payload)

            multicaster.multicastEvent(event)

            verify(exactly = 0) { recordRepository.save(any()) }
        }

        @Test
        fun `processes multiple outbox events independently`() {
            val event1 = PayloadApplicationEvent(this, TestOutboxEvent(aggregateId = "agg-1", data = "data1"))
            val event2 = PayloadApplicationEvent(this, TestOutboxEvent(aggregateId = "agg-2", data = "data2"))
            val recordSlots = mutableListOf<OutboxRecord>()

            every { recordRepository.save(any()) } answers {
                recordSlots.add(firstArg())
                mockk()
            }

            multicaster.multicastEvent(event1)
            multicaster.multicastEvent(event2)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots.map { it.aggregateId }).containsExactly("agg-1", "agg-2")
        }
    }

    @Nested
    @DisplayName("Event Serialization")
    inner class EventSerialization {
        @Test
        fun `serializes outbox event using OutboxEventSerializer`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)

            every { recordRepository.save(any()) } returns mockk()

            multicaster.multicastEvent(event)

            verify(exactly = 1) { eventSerializer.serialize(payload) }
        }

        @Test
        fun `stores serialized payload in record`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)
            val recordSlot = slot<OutboxRecord>()
            val customPayload = "custom-serialized-data"

            every { eventSerializer.serialize(any()) } returns customPayload
            every { recordRepository.save(capture(recordSlot)) } returns mockk()

            multicaster.multicastEvent(event)

            assertThat(recordSlot.captured.payload).isEqualTo(customPayload)
        }

        @Test
        fun `stops processing when serialization fails`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)

            every { eventSerializer.serialize(any()) } throws RuntimeException("Serialization error")

            try {
                multicaster.multicastEvent(event)
            } catch (ex: Exception) {
                assertThat(ex).hasMessage("Serialization error")
            }

            verify(exactly = 0) { recordRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("Configuration")
    inner class Configuration {
        @Test
        fun `publishes to listeners when publishAfterSave is true`() {
            val propsEnabled =
                OutboxProperties(
                    processing = OutboxProperties.Processing(publishAfterSave = true),
                )
            val multicasterEnabled =
                OutboxEventMulticaster(
                    beanFactory,
                    recordRepository,
                    eventSerializer,
                    propsEnabled,
                    clock,
                )

            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)

            every { recordRepository.save(any()) } returns mockk()

            multicasterEnabled.multicastEvent(event)

            verify(exactly = 1) { recordRepository.save(any()) }
        }

        @Test
        fun `saves to outbox even when publishAfterSave is false`() {
            val propsDisabled =
                OutboxProperties(
                    processing = OutboxProperties.Processing(publishAfterSave = false),
                )
            val multicasterDisabled =
                OutboxEventMulticaster(
                    beanFactory,
                    recordRepository,
                    eventSerializer,
                    propsDisabled,
                    clock,
                )

            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)

            every { recordRepository.save(any()) } returns mockk()

            multicasterDisabled.multicastEvent(event)

            verify(exactly = 1) { recordRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {
        @Test
        fun `fails when record repository throws exception`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)

            every { recordRepository.save(any()) } throws RuntimeException("Database error")

            try {
                multicaster.multicastEvent(event)
            } catch (ex: Exception) {
                assertThat(ex)
                    .isInstanceOf(RuntimeException::class.java)
                    .hasMessage("Database error")
            }

            verify(exactly = 1) { recordRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("Method Overloads")
    inner class MethodOverloads {
        @Test
        fun `multicastEvent works with eventType parameter`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)
            val eventType =
                org.springframework.core.ResolvableType
                    .forInstance(event)
            val recordSlot = slot<OutboxRecord>()

            every { recordRepository.save(capture(recordSlot)) } returns mockk()

            multicaster.multicastEvent(event, eventType)

            verify(exactly = 1) { recordRepository.save(any()) }
            assertThat(recordSlot.captured.aggregateId).isEqualTo("agg-1")
        }

        @Test
        fun `multicastEvent works without eventType parameter`() {
            val payload = TestOutboxEvent(aggregateId = "agg-1", data = "test")
            val event = PayloadApplicationEvent(this, payload)
            val recordSlot = slot<OutboxRecord>()

            every { recordRepository.save(capture(recordSlot)) } returns mockk()

            multicaster.multicastEvent(event)

            verify(exactly = 1) { recordRepository.save(any()) }
            assertThat(recordSlot.captured.aggregateId).isEqualTo("agg-1")
        }
    }

    @OutboxEvent(aggregateId = "aggregateId")
    private data class TestOutboxEvent(
        val aggregateId: String,
        val data: String,
    )

    private data class TestRegularEvent(
        val data: String,
    )
}
