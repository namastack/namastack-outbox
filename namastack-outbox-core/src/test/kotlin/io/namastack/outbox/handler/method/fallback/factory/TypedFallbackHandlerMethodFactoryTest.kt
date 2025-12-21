package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.TypedFallbackHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TypedFallbackHandlerMethodFactory")
class TypedFallbackHandlerMethodFactoryTest {
    private lateinit var factory: TypedFallbackHandlerMethodFactory

    @BeforeEach
    fun setUp() {
        factory = TypedFallbackHandlerMethodFactory()
    }

    @Test
    fun `supports returns true for typed fallback signature with specific type, metadata, context`() {
        val bean = TestTypedFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxRecordMetadata::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.supports(method)

        assertThat(result).isTrue()
    }

    @Test
    fun `supports returns false for method with wrong parameter count`() {
        val bean = TestInvalidFallbackHandler()
        val method = bean::class.java.getMethod("handleFailure", String::class.java)

        val result = factory.supports(method)

        assertThat(result).isFalse()
    }

    @Test
    fun `supports returns false for method with Any as first parameter`() {
        val bean = TestGenericFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                Any::class.java,
                OutboxRecordMetadata::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.supports(method)

        assertThat(result).isFalse()
    }

    @Test
    fun `supports returns true for different typed payload types`() {
        val bean1 = TestIntFallbackHandler()
        val method1 =
            bean1::class.java.getMethod(
                "handleFailure",
                Int::class.java,
                OutboxRecordMetadata::class.java,
                OutboxFailureContext::class.java,
            )

        val bean2 = TestListFallbackHandler()
        val method2 =
            bean2::class.java.getMethod(
                "handleFailure",
                List::class.java,
                OutboxRecordMetadata::class.java,
                OutboxFailureContext::class.java,
            )

        assertThat(factory.supports(method1)).isTrue()
        assertThat(factory.supports(method2)).isTrue()
    }

    @Test
    fun `create returns TypedFallbackHandlerMethod wrapper`() {
        val bean = TestTypedFallbackHandler()
        val method =
            bean::class.java.getMethod(
                "handleFailure",
                String::class.java,
                OutboxRecordMetadata::class.java,
                OutboxFailureContext::class.java,
            )

        val result = factory.create(bean, method)

        assertThat(result).isInstanceOf(TypedFallbackHandlerMethod::class.java)
    }

    @Test
    fun `createFromInterface creates TypedFallbackHandlerMethod from OutboxTypedHandlerWithFallback`() {
        val bean = TestOutboxTypedHandlerWithFallback()

        val result = factory.createFromInterface(bean)

        assertThat(result).isInstanceOf(TypedFallbackHandlerMethod::class.java)
    }

    // Test beans
    @Suppress("UNUSED_PARAMETER")
    class TestTypedFallbackHandler {
        fun handleFailure(
            payload: String,
            metadata: OutboxRecordMetadata,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestIntFallbackHandler {
        fun handleFailure(
            payload: Int,
            metadata: OutboxRecordMetadata,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestListFallbackHandler {
        fun handleFailure(
            payload: List<*>,
            metadata: OutboxRecordMetadata,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestGenericFallbackHandler {
        fun handleFailure(
            payload: Any,
            metadata: OutboxRecordMetadata,
            context: OutboxFailureContext,
        ) {
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class TestInvalidFallbackHandler {
        fun handleFailure(payload: String) {
        }
    }

    class TestOutboxTypedHandlerWithFallback : OutboxTypedHandlerWithFallback<String> {
        override fun handle(payload: String) {
        }

        override fun handleFailure(
            payload: String,
            metadata: OutboxRecordMetadata,
            context: OutboxFailureContext,
        ) {
        }
    }
}
