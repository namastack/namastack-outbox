package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class GenericHandlerMethodTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "record-key",
            handlerId = "handler-id",
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            context = mapOf("tenant" to "north"),
            failureCount = 0,
        )

    @Test
    fun `default payload support accepts every payload`() {
        val bean = RecordingHandler()
        val handler = GenericHandlerMethod(bean, method(RecordingHandler::class.java))

        assertThat(handler.supportsPayload("payload", metadata)).isTrue()
    }

    @Test
    fun `delegates payload support decision with payload and metadata`() {
        val bean = RecordingHandler()
        var received: Pair<Any, OutboxRecordMetadata>? = null
        val handler =
            GenericHandlerMethod(
                bean,
                method(RecordingHandler::class.java),
                payloadSupport = { payload, recordMetadata ->
                    received = payload to recordMetadata
                    false
                },
            )

        assertThat(handler.supportsPayload("payload", metadata)).isFalse()
        assertThat(received).isEqualTo("payload" to metadata)
    }

    @Test
    fun `invokes handler with payload and metadata`() {
        val bean = RecordingHandler()
        val handler = GenericHandlerMethod(bean, method(RecordingHandler::class.java))

        handler.invoke("payload", metadata)

        assertThat(bean.payload).isEqualTo("payload")
        assertThat(bean.metadata).isEqualTo(metadata)
    }

    @Test
    fun `rethrows original handler exception instead of reflection wrapper`() {
        val failure = IllegalStateException("handler failed")
        val bean = ThrowingHandler(failure)
        val handler = GenericHandlerMethod(bean, method(ThrowingHandler::class.java))

        assertThatThrownBy { handler.invoke("payload", metadata) }.isSameAs(failure)
    }

    private fun method(type: Class<*>) =
        type.getDeclaredMethod("handle", Any::class.java, OutboxRecordMetadata::class.java)

    private class RecordingHandler {
        var payload: Any? = null
        var metadata: OutboxRecordMetadata? = null

        fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            this.payload = payload
            this.metadata = metadata
        }
    }

    private class ThrowingHandler(
        private val failure: RuntimeException,
    ) {
        fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ): Unit = throw failure
    }
}
