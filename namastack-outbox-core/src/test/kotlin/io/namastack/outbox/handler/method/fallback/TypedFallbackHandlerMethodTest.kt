package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@DisplayName("TypedFallbackHandlerMethod")
class TypedFallbackHandlerMethodTest {
    @Test
    fun `invoke calls fallback handler method with typed payload`() {
        val bean = TestTypedFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = TypedFallbackHandlerMethod(bean, method)

        val payload = "test-payload"
        val context = createFailureContext()

        handler.invoke(payload, context)

        assertThat(bean.invocations).hasSize(1)
        assertThat(bean.invocations[0].payload).isEqualTo(payload)
        assertThat(bean.invocations[0].context).isEqualTo(context)
    }

    @Test
    fun `invoke works with different typed payloads`() {
        val stringBean = TestTypedFallbackHandler()
        val stringMethod =
            stringBean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxFailureContext::class.java,
            )
        val stringHandler = TypedFallbackHandlerMethod(stringBean, stringMethod)

        val intBean = TestIntFallbackHandler()
        val intMethod =
            intBean::class.java.getMethod(
                "handleFailure",
                Int::class.java,
                OutboxFailureContext::class.java,
            )
        val intHandler = TypedFallbackHandlerMethod(intBean, intMethod)

        val context = createFailureContext()

        stringHandler.invoke("test", context)
        intHandler.invoke(42, context)

        assertThat(stringBean.invocations).hasSize(1)
        assertThat(stringBean.invocations[0].payload).isEqualTo("test")
        assertThat(intBean.invocations).hasSize(1)
        assertThat(intBean.invocations[0].payload).isEqualTo(42)
    }

    @Test
    fun `invoke propagates exception from fallback handler`() {
        val bean = TestThrowingFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = TypedFallbackHandlerMethod(bean, method)

        val payload = "test-payload"
        val context = createFailureContext()

        assertThatThrownBy {
            handler.invoke(payload, context)
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("Fallback handler error")
    }

    @Test
    fun `id contains class name, method name, and parameter types`() {
        val bean = TestTypedFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = TypedFallbackHandlerMethod(bean, method)

        assertThat(handler.id).contains(bean::class.java.name)
        assertThat(handler.id).contains("handleFailure")
        assertThat(handler.id).contains("java.lang.String")
        assertThat(handler.id).contains("OutboxFailureContext")
    }

    @Test
    fun `constructor throws exception when method has wrong parameter count`() {
        val bean = TestInvalidHandler()
        val method = bean::class.java.getMethod("invalidMethod", String::class.java)

        assertThatThrownBy {
            TypedFallbackHandlerMethod(bean, method)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Fallback handler must have 2 parameters")
    }

    @Test
    fun `invoke with complex payload types`() {
        val bean = TestComplexTypeFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                List::class.java,
                OutboxFailureContext::class.java,
            )
        val handler = TypedFallbackHandlerMethod(bean, method)

        val payload = listOf("item1", "item2", "item3")
        val context = createFailureContext()

        handler.invoke(payload, context)

        assertThat(bean.invocations).hasSize(1)
        assertThat(bean.invocations[0].payload).isEqualTo(payload)
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
    class TestTypedFallbackHandler {
        data class Invocation(
            val payload: String,
            val context: OutboxFailureContext,
        )

        val invocations = mutableListOf<Invocation>()

        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) {
            invocations.add(Invocation(payload, context))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestIntFallbackHandler {
        data class Invocation(
            val payload: Int,
            val context: OutboxFailureContext,
        )

        val invocations = mutableListOf<Invocation>()

        fun handleFailure(
            payload: Int,
            context: OutboxFailureContext,
        ) {
            invocations.add(Invocation(payload, context))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestComplexTypeFallbackHandler {
        data class Invocation(
            val payload: List<*>,
            val context: OutboxFailureContext,
        )

        val invocations = mutableListOf<Invocation>()

        fun handleFailure(
            payload: List<*>,
            context: OutboxFailureContext,
        ) {
            invocations.add(Invocation(payload, context))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestThrowingFallbackHandler {
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ): Unit = throw RuntimeException("Fallback handler error")
    }

    @Suppress("UNUSED_PARAMETER")
    class TestInvalidHandler {
        fun invalidMethod(payload: String) {}
    }
}
