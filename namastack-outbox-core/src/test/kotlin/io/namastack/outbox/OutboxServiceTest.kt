package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@DisplayName("OutboxService")
class OutboxServiceTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    private val contextCollector = mockk<OutboxContextCollector>(relaxed = true)
    private val handlerRegistry = mockk<OutboxHandlerRegistry>(relaxed = true)
    private val outboxRecordRepository = mockk<OutboxRecordRepository>(relaxed = true)

    private lateinit var outboxService: OutboxService

    @BeforeEach
    fun setUp() {
        outboxService = OutboxService(contextCollector, handlerRegistry, outboxRecordRepository, clock)
    }

    @Nested
    @DisplayName("schedule()")
    inner class ScheduleTests {
        @Test
        fun `should create record for single handler`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlot.captured.key).isEqualTo("test-key")
            assertThat(recordSlot.captured.payload).isEqualTo(payload)
            assertThat(recordSlot.captured.handlerId).isEqualTo("handler1")
        }

        @Test
        fun `should create separate records for multiple handlers`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler1 = createTypedMockHandler("handler1")
            val handler2 = createTypedMockHandler("handler2")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler1, handler2)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots[0].handlerId).isEqualTo("handler1")
            assertThat(recordSlots[1].handlerId).isEqualTo("handler2")
        }

        @Test
        fun `should set status to NEW for created records`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlot.captured.status).isEqualTo(OutboxRecordStatus.NEW)
        }

        @Test
        fun `should include generic handlers in records`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val typedHandler = createTypedMockHandler("typed-handler")
            val genericHandler = createGenericMockHandler("generic-handler")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(typedHandler)
            every { handlerRegistry.getGenericHandlers() } returns listOf(genericHandler)
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots.map { it.handlerId }).contains("typed-handler", "generic-handler")
        }

        @Test
        fun `should deduplicate handlers`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler, handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlots).hasSize(1)
        }

        @Test
        fun `should set createdAt to current time`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlot.captured.createdAt).isEqualTo(now)
        }

        @Test
        fun `should persist all created records`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler1 = createTypedMockHandler("handler1")
            val handler2 = createTypedMockHandler("handler2")
            val handler3 = createTypedMockHandler("handler3")

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns
                listOf(handler1, handler2, handler3)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(any() as OutboxRecord<Any>) } returns mockk()

            outboxService.schedule(payload, "test-key", context)

            verify(exactly = 3) { outboxRecordRepository.save(any() as OutboxRecord<Any>) }
        }

        @Test
        fun `should handle empty handler list gracefully`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns emptyList()
            every { handlerRegistry.getGenericHandlers() } returns emptyList()

            outboxService.schedule(payload, "test-key", context)

            verify(exactly = 0) { outboxRecordRepository.save(any() as OutboxRecord<Any>) }
        }

        @Test
        fun `should only use generic handlers when no typed handlers exist`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val genericHandler = createGenericMockHandler("generic-only")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns emptyList()
            every { handlerRegistry.getGenericHandlers() } returns listOf(genericHandler)
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlot.captured.handlerId).isEqualTo("generic-only")
        }

        @Test
        fun `should create record with correct payload type`() {
            val payload = TestPayload("test-id-123")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat((recordSlot.captured.payload as TestPayload).testId).isEqualTo("test-id-123")
        }
    }

    @Nested
    @DisplayName("schedule(payload) - auto generated key")
    inner class ScheduleWithAutoGeneratedKeyTests {
        @Test
        fun `should generate UUID key when no key provided - Single Parameter`() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload)

            assertThat(recordSlot.captured.key).isNotEmpty()
            // Verify it's a UUID format (simple check)
            assertThat(recordSlot.captured.key)
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        }

        @Test
        fun `should generate UUID key when no key provided - Double Parameter`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, context)

            assertThat(recordSlot.captured.key).isNotEmpty()
            // Verify it's a UUID format (simple check)
            assertThat(recordSlot.captured.key)
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        }

        @Test
        fun `should generate different UUIDs for different calls`() {
            val payload1 = TestPayload("test-1")
            val payload2 = TestPayload("test-2")
            val handler = createTypedMockHandler("handler1")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload1)
            outboxService.schedule(payload2)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots[0].key).isNotEqualTo(recordSlots[1].key)
        }

        @Test
        fun `should delegate to schedule(payload, key) with generated UUID`() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload)

            verify { handlerRegistry.getHandlersForPayloadType(TestPayload::class) }
            verify { outboxRecordRepository.save(any() as OutboxRecord<Any>) }
        }

        @Test
        fun `should create records for all applicable handlers with auto-generated key`() {
            val payload = TestPayload("test-id")
            val handler1 = createTypedMockHandler("handler1")
            val handler2 = createTypedMockHandler("handler2")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns
                listOf(handler1, handler2)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots[0].key).isEqualTo(recordSlots[1].key)
        }

        @Test
        fun `should use auto-generated key for partition distribution`() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload)

            // UUID should be a valid format (36 chars including dashes)
            assertThat(recordSlot.captured.key).hasSize(36)
            assertThat(recordSlot.captured.key).matches(".*-.*-.*-.*-.*")
        }

        @Test
        fun `should set status to NEW with auto-generated key`() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload)

            assertThat(recordSlot.captured.status).isEqualTo(OutboxRecordStatus.NEW)
        }

        @Test
        fun `should include generic handlers with auto-generated key`() {
            val payload = TestPayload("test-id")
            val typedHandler = createTypedMockHandler("typed")
            val genericHandler = createGenericMockHandler("generic")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(typedHandler)
            every { handlerRegistry.getGenericHandlers() } returns listOf(genericHandler)
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload)

            assertThat(recordSlots).hasSize(2)
            assertThat(recordSlots.map { it.handlerId }).contains("typed", "generic")
        }
    }

    @Nested
    @DisplayName("Context Collection and Merging")
    inner class ContextCollectionTests {
        @Test
        fun `should use empty context if not provided - Single Parameter `() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload)

            assertThat(recordSlot.captured.context).isEmpty()
        }

        @Test
        fun `should use empty context if not provided - Double Parameters`() {
            val payload = TestPayload("test-id")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key")

            assertThat(recordSlot.captured.context).isEmpty()
        }

        @Test
        fun `should include additional context`() {
            val payload = TestPayload("test-id")
            val context = mapOf("key1" to "value1")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", context)

            assertThat(recordSlot.captured.context)
                .containsEntry("key1", "value1")
                .hasSize(1)
        }

        @Test
        fun `should include global context`() {
            val payload = TestPayload("test-id")
            val globalContext = mapOf("global1" to "value1", "global2" to "value2")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns globalContext
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key")

            assertThat(recordSlot.captured.context)
                .containsEntry("global1", "value1")
                .containsEntry("global2", "value2")
                .hasSize(2)
        }

        @Test
        fun `should merge global and additional context`() {
            val payload = TestPayload("test-id")
            val globalContext = mapOf("global1" to "value1", "global2" to "value2")
            val additionalContext = mapOf("additional1" to "value3")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns globalContext
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", additionalContext)

            assertThat(recordSlot.captured.context)
                .containsEntry("global1", "value1")
                .containsEntry("global2", "value2")
                .containsEntry("additional1", "value3")
                .hasSize(3)
        }

        @Test
        fun `should give precedence to additional context over global context on key conflicts`() {
            val payload = TestPayload("test-id")
            val globalContext = mapOf("key" to "global-value", "other" to "value")
            val additionalContext = mapOf("key" to "additional-value")
            val handler = createTypedMockHandler("handler1")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns globalContext
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(handler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key", additionalContext)

            assertThat(recordSlot.captured.context)
                .containsEntry("key", "additional-value")
                .containsEntry("other", "value")
                .hasSize(2)
        }
    }

    @Nested
    @DisplayName("collectHandlers() - Polymorphism")
    inner class PolymorphismTests {
        @Test
        fun `should collect handlers from superclass`() {
            val payload = TestPayload("test-id")
            val baseEventHandler = createTypedMockHandler("base-event-handler")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns emptyList()
            every { handlerRegistry.getHandlersForPayloadType(BaseEvent::class) } returns listOf(baseEventHandler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key")

            assertThat(recordSlot.captured.handlerId).isEqualTo("base-event-handler")
        }

        @Test
        fun `should collect handlers from interface`() {
            val payload = TestPayload("test-id")
            val domainEventHandler = createTypedMockHandler("domain-event-handler")
            val recordSlot = slot<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns emptyList()
            every { handlerRegistry.getHandlersForPayloadType(BaseEvent::class) } returns emptyList()
            every { handlerRegistry.getHandlersForPayloadType(DomainEvent::class) } returns listOf(domainEventHandler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlot)) } answers { recordSlot.captured }

            outboxService.schedule(payload, "test-key")

            assertThat(recordSlot.captured.handlerId).isEqualTo("domain-event-handler")
        }

        @Test
        fun `should collect handlers from payload type, superclass and interface`() {
            val payload = TestPayload("test-id")
            val typedHandler = createTypedMockHandler("typed")
            val baseEventHandler = createTypedMockHandler("base")
            val domainEventHandler = createTypedMockHandler("domain")
            val recordSlots = mutableListOf<OutboxRecord<Any>>()

            every { contextCollector.collectContext() } returns emptyMap()
            every { handlerRegistry.getHandlersForPayloadType(TestPayload::class) } returns listOf(typedHandler)
            every { handlerRegistry.getHandlersForPayloadType(BaseEvent::class) } returns listOf(baseEventHandler)
            every { handlerRegistry.getHandlersForPayloadType(DomainEvent::class) } returns listOf(domainEventHandler)
            every { handlerRegistry.getGenericHandlers() } returns emptyList()
            every { outboxRecordRepository.save(capture(recordSlots)) } answers { recordSlots.last() }

            outboxService.schedule(payload, "test-key")

            assertThat(recordSlots).hasSize(3)
            assertThat(recordSlots.map { it.handlerId }).containsExactly("typed", "base", "domain")
        }
    }

    private fun createTypedMockHandler(id: String): TypedHandlerMethod {
        val handler = mockk<TypedHandlerMethod>()
        every { handler.id } returns id
        return handler
    }

    private fun createGenericMockHandler(id: String): GenericHandlerMethod {
        val handler = mockk<GenericHandlerMethod>()
        every { handler.id } returns id
        return handler
    }

    interface DomainEvent

    open class BaseEvent(
        val id: String,
    ) : DomainEvent

    data class TestPayload(
        val testId: String,
    ) : BaseEvent(testId)
}
