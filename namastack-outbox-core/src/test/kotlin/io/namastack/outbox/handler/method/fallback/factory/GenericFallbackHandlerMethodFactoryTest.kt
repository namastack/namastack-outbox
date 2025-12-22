package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.GenericFallbackHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("GenericFallbackHandlerMethodFactory")
class GenericFallbackHandlerMethodFactoryTest {
    private lateinit var factory: GenericFallbackHandlerMethodFactory

    @BeforeEach
    fun setUp() {
        factory = GenericFallbackHandlerMethodFactory()
    }

    @Test
    fun `supports returns true for generic fallback signature with Any and context`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.supports(method)

        assertThat(result).isTrue()
    }

    @Test
    fun `supports returns false for method with wrong parameter count`() {
        val bean = TestInvalidFallbackHandler()
        val method = bean::class.java.getMethod("handleFailure", Any::class.java)

        val result = factory.supports(method)

        assertThat(result).isFalse()
    }

    @Test
    fun `supports returns false for method with typed payload instead of Any`() {
        val bean = TestTypedFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.supports(method)

        assertThat(result).isFalse()
    }

    @Test
    fun `supports returns false for method with wrong context type`() {
        val bean = TestWrongContextFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                String::class.java,
            )

        val result = factory.supports(method)

        assertThat(result).isFalse()
    }

    @Test
    fun `create returns GenericFallbackHandlerMethod wrapper`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.create(bean, method)

        assertThat(result).isInstanceOf(GenericFallbackHandlerMethod::class.java)
    }

    @Test
    fun `createFromInterface creates GenericFallbackHandlerMethod from OutboxHandlerWithFallback`() {
        val bean = TestOutboxHandlerWithFallback()

        val result = factory.createFromInterface(bean)

        assertThat(result).isInstanceOf(GenericFallbackHandlerMethod::class.java)
    }

    // Test beans
    @Suppress("UNUSED_PARAMETER")
    class TestGenericFallbackHandler {
        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestTypedFallbackHandler {
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestInvalidFallbackHandler {
        fun handleFailure(payload: Any) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestWrongContextFallbackHandler {
        fun handleFailure(
            payload: Any,
            context: String,
        ) {
        }
    }

    class TestOutboxHandlerWithFallback : OutboxHandlerWithFallback {
        override fun handle(
            payload: Any,
            metadata: io.namastack.outbox.handler.OutboxRecordMetadata,
        ) {
        }

        override fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
        }
    }
}
