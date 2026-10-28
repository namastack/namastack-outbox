package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MongoOutboxRecordEntityMapperTest {
    @Test
    fun `grouped keys survive persistence mapping without replacing storage identity`() {
        val storedKey = "~ns.pg1~billing:order:42"
        val payloadSerializer = mockk<OutboxPayloadSerializer>()
        every { payloadSerializer.serialize("payload") } returns "serialized-payload"
        every { payloadSerializer.deserialize("serialized-payload", String::class.java) } returns "payload"
        val recordMapper = MongoOutboxRecordEntityMapper(payloadSerializer)
        val record =
            OutboxRecord
                .Builder<String>()
                .key(storedKey)
                .payload("payload")
                .handlerId("handler-id")
                .build(Clock.systemUTC())

        val entity = recordMapper.map(record)
        val restored = recordMapper.map(entity)

        assertThat(entity.recordKey).isEqualTo(storedKey)
        assertThat(restored.key).isEqualTo(storedKey)
        assertThat(restored.businessKey).isEqualTo("order:42")
        assertThat(restored.processingGroup).isEqualTo("billing")
        assertThat(restored.partition).isEqualTo(record.partition)
        assertThat(restored.payload).isEqualTo("payload")
    }

    private val serializer = mockk<OutboxPayloadSerializer>()
    private val mapper = MongoOutboxRecordEntityMapper(serializer)

    @Test
    fun `exposes record details when payload type is unavailable`() {
        val now = Instant.now()
        val entity =
            MongoOutboxRecordEntity(
                id = "record-id",
                status = OutboxRecordStatus.NEW,
                recordKey = "~ns.pg1~billing:order:42",
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
        assertThat(exception.recordKey).isEqualTo("~ns.pg1~billing:order:42")
        assertThat(exception.businessKey).isEqualTo("order:42")
        assertThat(exception.processingGroup).isEqualTo("billing")
        assertThat(exception.payloadType).isEqualTo("example.MissingPayload")
        assertThat(exception.handlerId).isEqualTo("handler-id")
        assertThat(exception.cause).isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun `exposes record details when a payload dependency is unavailable`() {
        val now = Instant.now()
        val entity =
            MongoOutboxRecordEntity(
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
    fun `wraps linkage error propagated during payload deserialization`() {
        val now = Instant.now()
        val entity =
            MongoOutboxRecordEntity(
                id = "record-id",
                status = OutboxRecordStatus.NEW,
                recordKey = "record-key",
                recordType = String::class.java.name,
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
        val linkageFailure = NoClassDefFoundError("example/MissingDependency")
        every { serializer.deserialize(entity.payload, String::class.java) } throws linkageFailure

        val exception = assertThrows<OutboxPayloadTypeNotFoundException> { mapper.map(entity) }

        assertThat(exception.recordId).isEqualTo("record-id")
        assertThat(exception.recordKey).isEqualTo("record-key")
        assertThat(exception.payloadType).isEqualTo(String::class.java.name)
        assertThat(exception.handlerId).isEqualTo("handler-id")
        assertThat(exception.cause).isSameAs(linkageFailure)
    }

    @Test
    fun `maps domain record to entity`() {
        val payload = "test-payload"
        val context = mapOf("traceId" to "123")
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = "key",
                payload = payload,
                context = context,
                partition = 1,
                createdAt = Instant.now(),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = Instant.now(),
                handlerId = "handler",
                failureException = null,
            )

        every { serializer.serialize(payload) } returns "serialized-payload"
        every { serializer.serialize(context) } returns "serialized-context"

        val entity = mapper.map(record)

        assertThat(entity.id).isEqualTo(record.id)
        assertThat(entity.payload).isEqualTo("serialized-payload")
        assertThat(entity.context).isEqualTo("serialized-context")
        verify { serializer.serialize(payload) }
        verify { serializer.serialize(context) }
    }

    @Test
    fun `maps entity to domain record`() {
        val entity =
            MongoOutboxRecordEntity(
                id = UUID.randomUUID().toString(),
                status = OutboxRecordStatus.NEW,
                recordKey = "key",
                recordType = String::class.java.name,
                payload = "serialized-payload",
                context = "serialized-context",
                partitionNo = 1,
                createdAt = Instant.now(),
                completedAt = null,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = Instant.now(),
                handlerId = "handler",
            )

        every { serializer.deserialize("serialized-payload", any<Class<*>>()) } returns "test-payload"
        every { serializer.deserialize("serialized-context", any<Class<Map<String, String>>>()) } returns
            mapOf("traceId" to "123")

        val record = mapper.map(entity)

        assertThat(record.id).isEqualTo(entity.id)
        assertThat(record.payload).isEqualTo("test-payload")
        assertThat(record.context).isEqualTo(mapOf("traceId" to "123"))
    }
}
