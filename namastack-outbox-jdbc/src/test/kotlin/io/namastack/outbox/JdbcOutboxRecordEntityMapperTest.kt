package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import java.time.temporal.ChronoUnit.MINUTES

class JdbcOutboxRecordEntityMapperTest {
    private val innerJsonMapper = jsonMapper { addModule(kotlinModule()) }
    private val serializer = JacksonOutboxPayloadSerializer(innerJsonMapper)
    private val mapper = JdbcOutboxRecordEntityMapper(serializer)

    data class OrderCreatedEvent(
        val orderId: String,
        val amount: Double,
    )

    @Nested
    @DisplayName("map OutboxRecord to OutboxRecordEntity")
    inner class MapOutboxRecordTests {
        @Test
        fun `should map OrderCreatedEvent payload`() {
            val now = Instant.now()
            val completedAt = now.plus(5, MINUTES)
            val nextRetryAt = now.plus(10, MINUTES)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val record =
                OutboxRecord.restore(
                    id = "test-id",
                    recordKey = "record-123",
                    payload = event,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(entity.recordKey).isEqualTo("record-123")
            assertThat(
                entity.recordType,
            ).isEqualTo("io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent")
            assertThat(entity.payload).contains("orderId", "123", "100.5")
            assertThat(entity.context).isNull()
            assertThat(entity.createdAt).isEqualTo(now)
            assertThat(entity.completedAt).isEqualTo(completedAt)
            assertThat(entity.failureCount).isEqualTo(3)
            assertThat(entity.nextRetryAt).isEqualTo(nextRetryAt)
        }

        @Test
        fun `should map provided context`() {
            val now = Instant.now()
            val completedAt = now.plus(5, MINUTES)
            val nextRetryAt = now.plus(10, MINUTES)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val record =
                OutboxRecord.restore(
                    id = "test-id",
                    recordKey = "record-123",
                    payload = event,
                    context = mapOf("a" to "b", "x" to "y"),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.context).isEqualTo("{\"a\":\"b\",\"x\":\"y\"}")
        }

        @Test
        fun `should map empty context to null`() {
            val now = Instant.now()
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val record =
                OutboxRecord.restore(
                    id = "test-id",
                    recordKey = "record-123",
                    payload = event,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.context).isNull()
        }
    }

    @Nested
    @DisplayName("map OutboxRecordEntity to OutboxRecord")
    inner class MapEntityToOutboxRecordTests {
        @Test
        fun `should expose record details when payload type is unavailable`() {
            val now = Instant.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "record-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-key",
                    recordType = "example.MissingPayload",
                    payload = "{}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handler-id",
                )

            val exception = assertThrows<OutboxPayloadTypeNotFoundException> { mapper.map(entity) }

            assertThat(exception.recordId).isEqualTo("record-id")
            assertThat(exception.recordKey).isEqualTo("record-key")
            assertThat(exception.payloadType).isEqualTo("example.MissingPayload")
            assertThat(exception.handlerId).isEqualTo("handler-id")
            assertThat(exception.cause).isInstanceOf(ClassNotFoundException::class.java)
        }

        @Test
        fun `should expose record details when a payload dependency is unavailable`() {
            val now = Instant.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "record-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-key",
                    recordType = "example.DependentPayload",
                    payload = "{}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handler-id",
                )
            val thread = Thread.currentThread()
            val originalClassLoader = thread.contextClassLoader
            val classLoadingFailure = NoClassDefFoundError("example/MissingDependency")
            val exception =
                try {
                    thread.contextClassLoader =
                        object : ClassLoader(originalClassLoader) {
                            override fun loadClass(name: String): Class<*> {
                                if (name == entity.recordType) throw classLoadingFailure
                                return super.loadClass(name)
                            }
                        }
                    assertThrows<OutboxPayloadTypeNotFoundException> { mapper.map(entity) }
                } finally {
                    thread.contextClassLoader = originalClassLoader
                }

            assertThat(exception.recordId).isEqualTo("record-id")
            assertThat(exception.recordKey).isEqualTo("record-key")
            assertThat(exception.payloadType).isEqualTo("example.DependentPayload")
            assertThat(exception.handlerId).isEqualTo("handler-id")
            assertThat(exception.cause).isSameAs(classLoadingFailure)
        }

        @Test
        fun `should deserialize event payload correctly`() {
            val now = Instant.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.id).isEqualTo("test-id")
            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(record.key).isEqualTo("record-123")
            assertThat(record.payload).isInstanceOf(OrderCreatedEvent::class.java)
            val event = record.payload as OrderCreatedEvent
            assertThat(event.orderId).isEqualTo("123")
            assertThat(event.amount).isEqualTo(100.5)
            assertThat(record.context).isEmpty()
        }

        @Test
        fun `should deserialize context correctly`() {
            val now = Instant.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = "{\"a\":\"b\",\"x\":\"y\"}",
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.context).containsEntry("a", "b")
            assertThat(record.context).containsEntry("x", "y")
        }

        @Test
        fun `should map null context to empty map`() {
            val now = Instant.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.context).isEmpty()
        }
    }
}
