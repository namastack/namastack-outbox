package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("GenericHandlerMethodFactory")
class GenericHandlerMethodFactoryTest {
    private lateinit var factory: GenericHandlerMethodFactory

    @BeforeEach
    fun setUp() {
        factory = GenericHandlerMethodFactory()
    }

    @Nested
    @DisplayName("supports()")
    inner class SupportsTests {
        @Test
        fun `should support method with Any and OutboxRecordMetadata parameters`() {
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val result = factory.supports(method)

            assertThat(result).isTrue()
        }

        @Test
        fun `should not support method with no parameters`() {
            val method = TestHandler::class.java.getMethod("handleNoParams")

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with single parameter`() {
            val method = TestHandler::class.java.getMethod("handleSingleParam", String::class.java)

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with three parameters`() {
            val method =
                TestHandler::class.java.getMethod(
                    "handleThreeParams",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                    String::class.java,
                )

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with typed first parameter instead of Any`() {
            val method =
                TestHandler::class.java.getMethod(
                    "handleTyped",
                    String::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with wrong second parameter type`() {
            val method =
                TestHandler::class.java.getMethod(
                    "handleWrongMetadata",
                    Any::class.java,
                    String::class.java,
                )

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }
    }

    @Nested
    @DisplayName("create()")
    inner class CreateTests {
        @Test
        fun `should create generic handler method`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val result = factory.create(bean, method)

            assertThat(result).isInstanceOf(GenericHandlerMethod::class.java)
            assertThat(result.bean).isSameAs(bean)
            assertThat(result.method).isEqualTo(method)
        }

        @Test
        fun `should preserve bean reference`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val result = factory.create(bean, method) as GenericHandlerMethod

            assertThat(result.bean).isSameAs(bean)
        }

        @Test
        fun `should preserve method reference`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val result = factory.create(bean, method) as GenericHandlerMethod

            assertThat(result.method).isSameAs(method)
        }

        @Test
        fun `should generate unique handler IDs for different methods`() {
            val bean = TestHandler()
            val method1 =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val method2 =
                TestHandler::class.java.getMethod(
                    "handleGenericAlternative",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val handler1 = factory.create(bean, method1) as GenericHandlerMethod
            val handler2 = factory.create(bean, method2) as GenericHandlerMethod

            assertThat(handler1.id).isNotEqualTo(handler2.id)
        }
    }

    @Nested
    @DisplayName("createFromInterface()")
    inner class CreateFromInterfaceTests {
        @Test
        fun `should create generic handler from OutboxHandler implementation`() {
            val bean = TestHandlerImpl()

            val result = factory.createFromInterface(bean)

            assertThat(result).isInstanceOf(GenericHandlerMethod::class.java)
            assertThat(result.bean).isSameAs(bean)
        }

        @Test
        fun `should extract handle method from interface`() {
            val bean = TestHandlerImpl()

            val result = factory.createFromInterface(bean)

            assertThat(result.method.name).isEqualTo("handle")
            assertThat(result.method.parameterCount).isEqualTo(2)
        }

        @Test
        fun `should verify parameter types from interface`() {
            val bean = TestHandlerImpl()

            val result = factory.createFromInterface(bean)

            val parameterTypes = result.method.parameterTypes
            assertThat(parameterTypes[0]).isEqualTo(Any::class.java)
            assertThat(parameterTypes[1]).isEqualTo(OutboxRecordMetadata::class.java)
        }

        @Test
        fun `should work with different OutboxHandler implementations`() {
            val bean1 = TestHandlerImpl()
            val bean2 = AnotherTestHandlerImpl()

            val handler1 = factory.createFromInterface(bean1)
            val handler2 = factory.createFromInterface(bean2)

            assertThat(handler1.bean).isSameAs(bean1)
            assertThat(handler2.bean).isSameAs(bean2)
            assertThat(handler1.id).isNotEqualTo(handler2.id)
        }
    }

    @Nested
    @DisplayName("Handler ID Generation")
    inner class HandlerIdGenerationTests {
        @Test
        fun `should include class name in handler ID`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val handler = factory.create(bean, method) as GenericHandlerMethod

            assertThat(handler.id).contains("TestHandler")
        }

        @Test
        fun `should include method name in handler ID`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val handler = factory.create(bean, method) as GenericHandlerMethod

            assertThat(handler.id).contains("handleGeneric")
        }

        @Test
        fun `should include parameter types in handler ID`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val handler = factory.create(bean, method) as GenericHandlerMethod

            assertThat(handler.id).contains("java.lang.Object")
            assertThat(handler.id).contains("OutboxRecordMetadata")
        }

        @Test
        fun `should format handler ID with hash and parentheses`() {
            val bean = TestHandler()
            val method =
                TestHandler::class.java.getMethod(
                    "handleGeneric",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )

            val handler = factory.create(bean, method) as GenericHandlerMethod

            assertThat(handler.id).matches(".*#.*\\(.*\\)")
        }
    }

    @Nested
    @DisplayName("Handler Invocation")
    inner class HandlerInvocationTests {
        @Test
        fun `should invoke created handler with payload and metadata`() {
            val bean = InvocationTestHandler()
            val method =
                InvocationTestHandler::class.java.getMethod(
                    "handle",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val handler = factory.create(bean, method) as GenericHandlerMethod
            val payload = "test-payload"
            val metadata =
                OutboxRecordMetadata(
                    key = "test-key",
                    handlerId = "handler-1",
                    createdAt = Instant.now(),
                    context = mapOf("traceId" to "test-trace-id"),
                )

            bean.reset()
            handler.invoke(payload, metadata)

            assertThat(bean.lastPayload).isEqualTo(payload)
            assertThat(bean.lastMetadata).isEqualTo(metadata)
        }

        @Test
        fun `should handle different payload types`() {
            val bean = InvocationTestHandler()
            val method =
                InvocationTestHandler::class.java.getMethod(
                    "handle",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val handler = factory.create(bean, method) as GenericHandlerMethod
            val metadata =
                OutboxRecordMetadata(
                    key = "test-key",
                    handlerId = "handler-1",
                    createdAt = Instant.now(),
                    context = mapOf("traceId" to "test-trace-id"),
                )

            val payload1: Any = "string"
            val payload2: Any = 42
            val payload3: Any = listOf(1, 2, 3)

            handler.invoke(payload1, metadata)
            assertThat(bean.lastPayload).isEqualTo(payload1)

            handler.invoke(payload2, metadata)
            assertThat(bean.lastPayload).isEqualTo(payload2)

            handler.invoke(payload3, metadata)
            assertThat(bean.lastPayload).isEqualTo(payload3)
        }
    }

    class TestHandler {
        @Suppress("unused")
        fun handleGeneric(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            println(payload)
        }

        @Suppress("unused")
        fun handleGenericAlternative(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            println(payload)
        }

        @Suppress("unused")
        fun handleNoParams() {
            println("no params")
        }

        @Suppress("unused")
        fun handleSingleParam(payload: String) {
            println(payload)
        }

        @Suppress("unused")
        fun handleTyped(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) {
            println(payload)
        }

        @Suppress("unused")
        fun handleWrongMetadata(
            payload: Any,
            extra: String,
        ) {
            println(payload)
        }

        @Suppress("unused")
        fun handleThreeParams(
            payload: Any,
            metadata: OutboxRecordMetadata,
            extra: String,
        ) {
            println(payload)
        }
    }

    class TestHandlerImpl : OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            println(payload)
        }
    }

    class AnotherTestHandlerImpl : OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            println(payload)
        }
    }

    class InvocationTestHandler : OutboxHandler {
        var lastPayload: Any? = null
        var lastMetadata: OutboxRecordMetadata? = null

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            lastPayload = payload
            lastMetadata = metadata
        }

        fun reset() {
            lastPayload = null
            lastMetadata = null
        }
    }
}
