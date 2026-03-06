package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TypedHandlerMethod")
class TypedHandlerMethodTest {
    @Nested
    @DisplayName("invoke() with 1-parameter signature")
    inner class InvokeOneParamTests {
        @Test
        fun `should invoke handler with payload only`() {
            val handler = TestHandlerOneParam()
            val method = TestHandlerOneParam::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)
            val metadata = createMetadata()

            typedHandler.invoke("test-payload", metadata)

            assertThat(handler.receivedPayload).isEqualTo("test-payload")
            assertThat(handler.receivedMetadata).isNull()
        }

        @Test
        fun `should invoke handler with custom type payload only`() {
            val handler = TestHandlerOneParamCustomType()
            val method = TestHandlerOneParamCustomType::class.java.getMethod("handle", TestPayload::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)
            val metadata = createMetadata()
            val payload = TestPayload("test-id")

            typedHandler.invoke(payload, metadata)

            assertThat(handler.receivedPayload).isEqualTo(payload)
            assertThat(handler.receivedMetadata).isNull()
        }
    }

    @Nested
    @DisplayName("invoke() with 2-parameter signature")
    inner class InvokeTwoParamTests {
        @Test
        fun `should invoke handler with payload and metadata`() {
            val handler = TestHandlerTwoParams()
            val method =
                TestHandlerTwoParams::class.java.getMethod(
                    "handle",
                    String::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val typedHandler = TypedHandlerMethod(handler, method)
            val metadata = createMetadata()

            typedHandler.invoke("test-payload", metadata)

            assertThat(handler.receivedPayload).isEqualTo("test-payload")
            assertThat(handler.receivedMetadata).isSameAs(metadata)
        }

        @Test
        fun `should invoke handler with custom type payload and metadata`() {
            val handler = TestHandlerTwoParamsCustomType()
            val method =
                TestHandlerTwoParamsCustomType::class.java.getMethod(
                    "handle",
                    TestPayload::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val typedHandler = TypedHandlerMethod(handler, method)
            val metadata = createMetadata()
            val payload = TestPayload("test-id")

            typedHandler.invoke(payload, metadata)

            assertThat(handler.receivedPayload).isEqualTo(payload)
            assertThat(handler.receivedMetadata).isSameAs(metadata)
        }
    }

    @Nested
    @DisplayName("paramType property")
    inner class ParamTypeTests {
        @Test
        fun `should extract String param type from 1-param method`() {
            val handler = TestHandlerOneParam()
            val method = TestHandlerOneParam::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.paramType).isEqualTo(String::class)
        }

        @Test
        fun `should extract custom param type from 1-param method`() {
            val handler = TestHandlerOneParamCustomType()
            val method = TestHandlerOneParamCustomType::class.java.getMethod("handle", TestPayload::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.paramType).isEqualTo(TestPayload::class)
        }

        @Test
        fun `should extract String param type from 2-param method`() {
            val handler = TestHandlerTwoParams()
            val method =
                TestHandlerTwoParams::class.java.getMethod(
                    "handle",
                    String::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.paramType).isEqualTo(String::class)
        }

        @Test
        fun `should extract custom param type from 2-param method`() {
            val handler = TestHandlerTwoParamsCustomType()
            val method =
                TestHandlerTwoParamsCustomType::class.java.getMethod(
                    "handle",
                    TestPayload::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.paramType).isEqualTo(TestPayload::class)
        }
    }

    private fun createMetadata(): OutboxRecordMetadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = java.time.Instant.now(),
            context = emptyMap(),
        )

    class TestHandlerOneParam {
        var receivedPayload: String? = null
        var receivedMetadata: OutboxRecordMetadata? = null

        fun handle(payload: String) {
            receivedPayload = payload
        }
    }

    class TestHandlerOneParamCustomType {
        var receivedPayload: TestPayload? = null
        var receivedMetadata: OutboxRecordMetadata? = null

        fun handle(payload: TestPayload) {
            receivedPayload = payload
        }
    }

    class TestHandlerTwoParams {
        var receivedPayload: String? = null
        var receivedMetadata: OutboxRecordMetadata? = null

        fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) {
            receivedPayload = payload
            receivedMetadata = metadata
        }
    }

    class TestHandlerTwoParamsCustomType {
        var receivedPayload: TestPayload? = null
        var receivedMetadata: OutboxRecordMetadata? = null

        fun handle(
            payload: TestPayload,
            metadata: OutboxRecordMetadata,
        ) {
            receivedPayload = payload
            receivedMetadata = metadata
        }
    }

    data class TestPayload(
        val id: String,
    )
}
