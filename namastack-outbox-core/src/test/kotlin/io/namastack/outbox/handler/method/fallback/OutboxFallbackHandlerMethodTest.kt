package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxFallbackHandlerMethodTest {
    private val context =
        OutboxFailureContext(
            recordId = "record-id",
            recordKey = "record-key",
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            failureCount = 3,
            lastFailure = IllegalStateException("primary failed"),
            handlerId = "handler-id",
            retriesExhausted = true,
            nonRetryableException = false,
            context = emptyMap(),
        )

    @Test
    fun `invokes fallback with payload and context`() {
        val bean = RecordingFallback()
        val fallback = OutboxFallbackHandlerMethod(bean, fallbackMethod(RecordingFallback::class.java))

        fallback.invoke("payload", context)

        assertThat(bean.payload).isEqualTo("payload")
        assertThat(bean.context).isEqualTo(context)
    }

    @Test
    fun `rejects fallback method with wrong parameter count`() {
        val bean = InvalidFallback()
        val method = bean::class.java.getDeclaredMethod("handleFailure", Any::class.java)

        assertThatThrownBy { OutboxFallbackHandlerMethod(bean, method) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must have 2 parameters")
    }

    @Test
    fun `rethrows original fallback exception instead of reflection wrapper`() {
        val failure = IllegalArgumentException("fallback failed")
        val bean = ThrowingFallback(failure)
        val fallback = OutboxFallbackHandlerMethod(bean, fallbackMethod(ThrowingFallback::class.java))

        assertThatThrownBy { fallback.invoke("payload", context) }.isSameAs(failure)
    }

    private fun fallbackMethod(type: Class<*>) =
        type.getDeclaredMethod("handleFailure", Any::class.java, OutboxFailureContext::class.java)

    private class RecordingFallback {
        var payload: Any? = null
        var context: OutboxFailureContext? = null

        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
            this.payload = payload
            this.context = context
        }
    }

    private class InvalidFallback {
        fun handleFailure(payload: Any) = Unit
    }

    private class ThrowingFallback(
        private val failure: RuntimeException,
    ) {
        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ): Unit = throw failure
    }
}
