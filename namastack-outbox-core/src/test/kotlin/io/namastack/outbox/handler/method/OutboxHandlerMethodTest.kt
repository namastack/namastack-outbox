package io.namastack.outbox.handler.method

import io.mockk.mockk
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

@DisplayName("TypedHandlerMethod")
class TypedHandlerMethodTest {
    private lateinit var handler: TypedHandlerMethod
    private lateinit var handlerBean: TestHandler
    private lateinit var method: Method

    @BeforeEach
    fun setUp() {
        handlerBean = TestHandler()
        method = TestHandler::class.java.getMethod("handle", String::class.java)
        handler = TypedHandlerMethod(handlerBean, method, String::class)
    }

    @Nested
    @DisplayName("invoke()")
    inner class InvokeTests {
        @Test
        fun `should invoke handler method with payload`() {
            val payload = "test-payload"

            handler.invoke(payload)

            assertThat(handlerBean.lastInvokedPayload).isEqualTo(payload)
        }

        @Test
        fun `should handle different payload values`() {
            val payload1 = "first"
            val payload2 = "second"

            handler.invoke(payload1)
            assertThat(handlerBean.lastInvokedPayload).isEqualTo(payload1)

            handler.invoke(payload2)
            assertThat(handlerBean.lastInvokedPayload).isEqualTo(payload2)
        }

        @Test
        fun `should throw exception on invocation error`() {
            val errorPayload = "ERROR"
            handlerBean.shouldThrow = true

            val exception =
                try {
                    handler.invoke(errorPayload)
                    null
                } catch (e: Exception) {
                    e
                }

            assertThat(exception).isNotNull()
        }
    }

    @Nested
    @DisplayName("Handler ID Generation")
    inner class IdGenerationTests {
        @Test
        fun `should generate unique ID from class and method`() {
            assertThat(handler.id).contains("TestHandler")
            assertThat(handler.id).contains("handle")
        }

        @Test
        fun `should include parameter types in ID`() {
            assertThat(handler.id).contains("java.lang.String")
        }

        @Test
        fun `should format ID with hash and parentheses`() {
            assertThat(handler.id).matches(".*#.*\\(.*\\)")
        }
    }

    @Nested
    @DisplayName("Handler Properties")
    inner class HandlerPropertiesTests {
        @Test
        fun `should store bean reference`() {
            assertThat(handler.bean).isSameAs(handlerBean)
        }

        @Test
        fun `should store method reference`() {
            assertThat(handler.method).isEqualTo(method)
        }

        @Test
        fun `should store parameter type`() {
            assertThat(handler.paramType).isEqualTo(String::class)
        }
    }

    class TestHandler {
        var lastInvokedPayload: String? = null
        var shouldThrow: Boolean = false

        @Suppress("unused")
        fun handle(payload: String) {
            if (shouldThrow) {
                throw IllegalArgumentException("Handler error")
            }
            lastInvokedPayload = payload
        }
    }
}

@DisplayName("GenericHandlerMethod")
class GenericHandlerMethodTest {
    private lateinit var handler: GenericHandlerMethod
    private lateinit var handlerBean: TestGenericHandler
    private lateinit var method: Method

    @BeforeEach
    fun setUp() {
        handlerBean = TestGenericHandler()
        method = TestGenericHandler::class.java.getMethod("handle", Any::class.java, OutboxRecordMetadata::class.java)
        handler = GenericHandlerMethod(handlerBean, method)
    }

    @Nested
    @DisplayName("invoke()")
    inner class InvokeTests {
        private val metadata = mockk<OutboxRecordMetadata>()

        @Test
        fun `should invoke handler method with any payload`() {
            val payload: Any = "test-payload"

            handler.invoke(payload, metadata)

            assertThat(handlerBean.lastInvokedPayload).isEqualTo(payload)
        }

        @Test
        fun `should accept different payload types`() {
            val stringPayload: Any = "string"
            val intPayload: Any = 42
            val objectPayload: Any = mapOf("key" to "value")

            handler.invoke(stringPayload, metadata)
            assertThat(handlerBean.lastInvokedPayload).isEqualTo(stringPayload)

            handler.invoke(intPayload, metadata)
            assertThat(handlerBean.lastInvokedPayload).isEqualTo(intPayload)

            handler.invoke(objectPayload, metadata)
            assertThat(handlerBean.lastInvokedPayload).isEqualTo(objectPayload)
        }

        @Test
        fun `should throw exception on invocation error`() {
            val errorPayload: Any = "ERROR"
            handlerBean.shouldThrow = true

            val exception =
                try {
                    handler.invoke(errorPayload, metadata)
                    null
                } catch (e: Exception) {
                    e
                }

            assertThat(exception).isNotNull()
        }
    }

    @Nested
    @DisplayName("Handler ID Generation")
    inner class IdGenerationTests {
        @Test
        fun `should generate unique ID from class and method`() {
            assertThat(handler.id).contains("TestGenericHandler")
            assertThat(handler.id).contains("handle")
        }

        @Test
        fun `should format ID with hash and parentheses`() {
            assertThat(handler.id).matches(".*#.*\\(.*\\)")
        }
    }

    @Nested
    @DisplayName("Handler Properties")
    inner class HandlerPropertiesTests {
        @Test
        fun `should store bean reference`() {
            assertThat(handler.bean).isSameAs(handlerBean)
        }

        @Test
        fun `should store method reference`() {
            assertThat(handler.method).isEqualTo(method)
        }
    }

    class TestGenericHandler {
        var lastInvokedPayload: Any? = null
        var shouldThrow: Boolean = false

        @Suppress("unused")
        fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            if (shouldThrow) {
                throw IllegalArgumentException("Handler error")
            }
            lastInvokedPayload = payload
        }
    }
}
