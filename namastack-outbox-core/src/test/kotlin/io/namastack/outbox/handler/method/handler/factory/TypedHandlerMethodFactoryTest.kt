package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TypedHandlerMethodFactory")
class TypedHandlerMethodFactoryTest {
    private lateinit var factory: TypedHandlerMethodFactory

    @BeforeEach
    fun setUp() {
        factory = TypedHandlerMethodFactory()
    }

    @Nested
    @DisplayName("supports()")
    inner class SupportsTests {
        @Test
        fun `should support method with single String parameter`() {
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val result = factory.supports(method)

            assertThat(result).isTrue()
        }

        @Test
        fun `should support method with single Int parameter`() {
            val method = TestHandler::class.java.getMethod("handleInt", Int::class.java)

            val result = factory.supports(method)

            assertThat(result).isTrue()
        }

        @Test
        fun `should support method with single custom type parameter`() {
            val method = TestHandler::class.java.getMethod("handlePayload", TestPayload::class.java)

            val result = factory.supports(method)

            assertThat(result).isTrue()
        }

        @Test
        fun `should not support method with Any parameter`() {
            val method = TestHandler::class.java.getMethod("handleAny", Any::class.java)

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with no parameters`() {
            val method = TestHandler::class.java.getMethod("handleNoParams")

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }

        @Test
        fun `should not support method with two parameters`() {
            val method =
                TestHandler::class.java.getMethod(
                    "handleTwoParams",
                    String::class.java,
                    Int::class.java,
                )

            val result = factory.supports(method)

            assertThat(result).isFalse()
        }
    }

    @Nested
    @DisplayName("create()")
    inner class CreateTests {
        @Test
        fun `should create typed handler method for String parameter`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val result = factory.create(bean, method)

            assertThat(result).isInstanceOf(TypedHandlerMethod::class.java)
            assertThat(result.bean).isSameAs(bean)
            assertThat(result.method).isEqualTo(method)
            assertThat(result.method.parameterTypes.first()).isEqualTo(String::class.java)
        }

        @Test
        fun `should create typed handler method for custom type parameter`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handlePayload", TestPayload::class.java)

            val result = factory.create(bean, method)

            assertThat(result).isInstanceOf(TypedHandlerMethod::class.java)
            assertThat(result.method.parameterTypes.first()).isEqualTo(TestPayload::class.java)
        }

        @Test
        fun `should generate unique handler IDs for different methods`() {
            val bean = TestHandler()
            val stringMethod = TestHandler::class.java.getMethod("handleString", String::class.java)
            val intMethod = TestHandler::class.java.getMethod("handleInt", Int::class.java)

            val handler1 = factory.create(bean, stringMethod) as TypedHandlerMethod
            val handler2 = factory.create(bean, intMethod) as TypedHandlerMethod

            assertThat(handler1.id).isNotEqualTo(handler2.id)
        }

        @Test
        fun `should preserve bean reference`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val result = factory.create(bean, method) as TypedHandlerMethod

            assertThat(result.bean).isSameAs(bean)
        }

        @Test
        fun `should preserve method reference`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val result = factory.create(bean, method) as TypedHandlerMethod

            assertThat(result.method).isSameAs(method)
        }
    }

    @Nested
    @DisplayName("createFromInterface()")
    inner class CreateFromInterfaceTests {
        @Test
        fun `should create typed handler from OutboxTypedHandler String implementation`() {
            val bean = StringHandlerImpl()

            val result = factory.createFromInterface(bean)

            assertThat(result).isInstanceOf(TypedHandlerMethod::class.java)
            assertThat(result.bean).isSameAs(bean)
            assertThat(result.method.parameterTypes.first()).isEqualTo(String::class.java)
        }

        @Test
        fun `should create typed handler from OutboxTypedHandler TestPayload implementation`() {
            val bean = PayloadHandlerImpl()

            val result = factory.createFromInterface(bean)

            assertThat(result).isInstanceOf(TypedHandlerMethod::class.java)
            assertThat(result.method.parameterTypes.first()).isEqualTo(TestPayload::class.java)
        }

        @Test
        fun `should extract handle method from interface implementation`() {
            val bean = StringHandlerImpl()

            val result = factory.createFromInterface(bean)

            assertThat(result.method.name).isEqualTo("handle")
            assertThat(result.method.parameterCount).isEqualTo(1)
        }

        @Test
        fun `should generate unique IDs for different interface implementations`() {
            val bean1 = StringHandlerImpl()
            val bean2 = PayloadHandlerImpl()

            val handler1 = factory.createFromInterface(bean1)
            val handler2 = factory.createFromInterface(bean2)

            assertThat(handler1.id).isNotEqualTo(handler2.id)
        }
    }

    @Nested
    @DisplayName("Handler ID Generation")
    inner class HandlerIdGenerationTests {
        @Test
        fun `should include class name in handler ID`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val handler = factory.create(bean, method) as TypedHandlerMethod

            assertThat(handler.id).contains("TestHandler")
        }

        @Test
        fun `should include method name in handler ID`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val handler = factory.create(bean, method) as TypedHandlerMethod

            assertThat(handler.id).contains("handleString")
        }

        @Test
        fun `should include parameter type in handler ID`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val handler = factory.create(bean, method) as TypedHandlerMethod

            assertThat(handler.id).contains("String")
        }

        @Test
        fun `should format handler ID with hash and parentheses`() {
            val bean = TestHandler()
            val method = TestHandler::class.java.getMethod("handleString", String::class.java)

            val handler = factory.create(bean, method) as TypedHandlerMethod

            assertThat(handler.id).matches(".*#.*\\(.*\\)")
        }
    }

    class TestHandler {
        @Suppress("unused")
        fun handleString(payload: String) {
            println(payload)
        }

        @Suppress("unused")
        fun handleInt(payload: Int) {
            println(payload)
        }

        @Suppress("unused")
        fun handlePayload(payload: TestPayload) {
            println(payload)
        }

        @Suppress("unused")
        fun handleAny(payload: Any) {
            println(payload)
        }

        @Suppress("unused")
        fun handleNoParams() {
            println("no params")
        }

        @Suppress("unused")
        fun handleTwoParams(
            payload: String,
            extra: Int,
        ) {
            println(payload)
        }
    }

    class StringHandlerImpl : OutboxTypedHandler<String> {
        override fun handle(payload: String) {
            println(payload)
        }
    }

    class PayloadHandlerImpl : OutboxTypedHandler<TestPayload> {
        override fun handle(payload: TestPayload) {
            println(payload)
        }
    }

    data class TestPayload(
        val id: String = "test",
    )
}
