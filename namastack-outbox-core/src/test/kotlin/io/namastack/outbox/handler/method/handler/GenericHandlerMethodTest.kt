package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("GenericHandlerMethod")
class GenericHandlerMethodTest {
    @Test
    fun `supportsScheduling delegates to OutboxHandler supports`() {
        val bean = ConditionalGenericHandler(supported = false)
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )
        val handler = GenericHandlerMethod(bean, method)

        val supported = handler.supportsScheduling("payload", metadata())

        assertThat(supported).isFalse()
    }

    @Test
    fun `supportsScheduling defaults to true for non OutboxHandler beans`() {
        val bean = AnnotatedStyleGenericHandler()
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )
        val handler = GenericHandlerMethod(bean, method)

        val supported = handler.supportsScheduling("payload", metadata())

        assertThat(supported).isTrue()
    }

    private fun metadata() =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    private class ConditionalGenericHandler(
        private val supported: Boolean,
    ) : OutboxHandler {
        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ): Boolean = supported

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op
        }
    }

    private class AnnotatedStyleGenericHandler {
        @Suppress("UNUSED_PARAMETER")
        fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op
        }
    }
}
