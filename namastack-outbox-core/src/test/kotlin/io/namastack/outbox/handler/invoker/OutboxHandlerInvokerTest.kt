package io.namastack.outbox.handler.invoker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxHandlerInvoker")
class OutboxHandlerInvokerTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>()
    private lateinit var invoker: OutboxHandlerInvoker
    private val now = Instant.now()

    @BeforeEach
    fun setUp() {
        invoker = OutboxHandlerInvoker(handlerRegistry)
    }

    @Test
    fun `dispatches to typed handler with payload and metadata derived from the record`() {
        val (record, metadata) = createRecord(handlerId = "handler-1")
        val typedHandler = mockk<TypedHandlerMethod>()

        every { handlerRegistry.getHandlerById("handler-1") } returns typedHandler
        every { typedHandler.invoke(any(), any()) } returns Unit

        invoker.dispatch(record)

        verify { typedHandler.invoke(record.payload!!, metadata) }
    }

    @Test
    fun `dispatches to generic handler with payload and metadata`() {
        val (record, metadata) = createRecord(handlerId = "handler-2")
        val genericHandler = mockk<GenericHandlerMethod>()

        every { handlerRegistry.getHandlerById("handler-2") } returns genericHandler
        every { genericHandler.invoke(any(), any()) } returns Unit

        invoker.dispatch(record)

        verify { genericHandler.invoke(record.payload!!, metadata) }
    }

    @Test
    fun `skips processing when payload is null`() {
        val (record, _) = createRecord(payload = null)

        invoker.dispatch(record)

        verify(exactly = 0) { handlerRegistry.getHandlerById(any()) }
    }

    @Test
    fun `throws IllegalStateException when handler not found`() {
        val (record, _) = createRecord(handlerId = "unknown-handler")

        every { handlerRegistry.getHandlerById("unknown-handler") } returns null

        assertThatThrownBy {
            invoker.dispatch(record)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No handler with id unknown-handler")
    }

    @Test
    fun `propagates exception from typed handler`() {
        val (record, _) = createRecord(handlerId = "failing-handler")
        val typedHandler = mockk<TypedHandlerMethod>()
        val exception = RuntimeException("Handler error")

        every { handlerRegistry.getHandlerById("failing-handler") } returns typedHandler
        every { typedHandler.invoke(any(), any()) } throws exception

        assertThatThrownBy {
            invoker.dispatch(record)
        }.isSameAs(exception)
    }

    @Test
    fun `propagates exception from generic handler`() {
        val (record, _) = createRecord(handlerId = "failing-handler")
        val genericHandler = mockk<GenericHandlerMethod>()
        val exception = IllegalStateException("Handler error")

        every { handlerRegistry.getHandlerById("failing-handler") } returns genericHandler
        every { genericHandler.invoke(any(), any()) } throws exception

        assertThatThrownBy {
            invoker.dispatch(record)
        }.isSameAs(exception)
    }

    private fun createRecord(
        id: String = "test-payload",
        key: String = "test-key",
        handlerId: String = "handler-1",
        payload: Any? = "test-payload",
        createdAt: Instant = now,
        context: Map<String, String> = emptyMap(),
    ): Pair<OutboxRecord<Any?>, OutboxRecordMetadata> {
        val record =
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = payload,
                context = context,
                createdAt = createdAt,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                partition = 1,
                nextRetryAt = createdAt,
                handlerId = handlerId,
            )
        val metadata =
            OutboxRecordMetadata(
                key = key,
                handlerId = handlerId,
                createdAt = createdAt,
                context = context,
            )
        return record to metadata
    }
}
