package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@DisplayName("GenericFallbackHandlerMethod")
class GenericFallbackHandlerMethodTest {
    @Test
    fun `invoke calls fallback handler method with any payload type`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = GenericFallbackHandlerMethod(bean, method)

        val payload = "test-payload"
        val context = createFailureContext()

        handler.invoke(payload, context)

        assertThat(bean.invocations).hasSize(1)
        assertThat(bean.invocations[0].payload).isEqualTo(payload)
        assertThat(bean.invocations[0].context).isEqualTo(context)
    }

    @Test
    fun `invoke works with different payload types`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = GenericFallbackHandlerMethod(bean, method)

        val stringPayload = "string"
        val intPayload = 42
        val listPayload = listOf(1, 2, 3)
        val context = createFailureContext()

        handler.invoke(stringPayload, context)
        handler.invoke(intPayload, context)
        handler.invoke(listPayload, context)

        assertThat(bean.invocations).hasSize(3)
        assertThat(bean.invocations[0].payload).isEqualTo(stringPayload)
        assertThat(bean.invocations[1].payload).isEqualTo(intPayload)
        assertThat(bean.invocations[2].payload).isEqualTo(listPayload)
    }

    @Test
    fun `invoke propagates exception from fallback handler`() {
        val bean = TestThrowingFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = GenericFallbackHandlerMethod(bean, method)

        val payload = "test-payload"
        val context = createFailureContext()

        assertThatThrownBy {
            handler.invoke(payload, context)
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("Fallback handler error")
    }

    @Test
    fun `id contains class name, method name, and parameter types`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = GenericFallbackHandlerMethod(bean, method)

        assertThat(handler.id).contains(bean::class.java.name)
        assertThat(handler.id).contains("handleFailure")
        assertThat(handler.id).contains("java.lang.Object")
        assertThat(handler.id).contains("OutboxFailureContext")
    }

    @Test
    fun `constructor throws exception when method has wrong parameter count`() {
        val bean = TestInvalidHandler()
        val method = bean::class.java.getMethod("invalidMethod", Any::class.java)

        assertThatThrownBy {
            GenericFallbackHandlerMethod(bean, method)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Fallback handler must have 2 parameters")
    }

    private fun createFailureContext(): OutboxFailureContext =
        OutboxFailureContext(
            recordId = "test-record-id",
            recordKey = "test-record-key",
            createdAt = OffsetDateTime.now(),
            failureCount = 3,
            lastFailure = RuntimeException("Test exception"),
            handlerId = "test-handler-id",
            retriesExhausted = true,
            nonRetryableException = false,
        )

    // Test beans
    @Suppress("UNUSED_PARAMETER")
    class TestGenericFallbackHandler {
        data class Invocation(
            val payload: Any,
            val context: OutboxFailureContext,
        )

        val invocations = mutableListOf<Invocation>()

        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
            invocations.add(Invocation(payload, context))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestThrowingFallbackHandler {
        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ): Unit = throw RuntimeException("Fallback handler error")
    }

    @Suppress("UNUSED_PARAMETER")
    class TestInvalidHandler {
        fun invalidMethod(payload: Any) {}
    }
}
