package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("GenericHandlerAnnotationMethod")
class GenericHandlerAnnotationMethodTest {
    @Test
    fun `supportsScheduling defaults to true for non OutboxHandler beans`() {
        val bean = AnnotatedStyleGenericHandler()
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )
        val handler = GenericHandlerAnnotationMethod(bean, method)

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
