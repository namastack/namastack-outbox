package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxHandlerId
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory

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

    @Nested
    @DisplayName("CGLIB Proxy ID Stability")
    inner class ProxyIdStabilityTests {
        private val targetBean = TestHandler()
        private val method = TestHandler::class.java.getMethod("handle", String::class.java)

        @Test
        fun `handler ID uses target class name not CGLIB proxy class name`() {
            val proxiedBean = createCglibProxy(targetBean)
            val handlerFromTarget = TypedHandlerMethod(targetBean, method)
            val handlerFromProxy = TypedHandlerMethod(proxiedBean, method)

            assertThat(handlerFromProxy.id).isEqualTo(handlerFromTarget.id)
        }

        @Test
        fun `handler ID contains original class name not proxy class name`() {
            val proxiedBean = createCglibProxy(targetBean)
            val handlerFromProxy = TypedHandlerMethod(proxiedBean, method)

            assertThat(handlerFromProxy.id).contains("TestHandler")
            assertThat(handlerFromProxy.id).doesNotContain("CGLIB")
            assertThat(handlerFromProxy.id).doesNotContain("$$")
        }

        private fun createCglibProxy(bean: Any): Any {
            val proxyFactory = ProxyFactory(bean)
            proxyFactory.isProxyTargetClass = true
            proxyFactory.addAdvice(org.aopalliance.intercept.MethodInterceptor { it.proceed() })

            return proxyFactory.proxy
        }

        open inner class TestHandler {
            open fun handle(payload: String) {
                // no-op
            }
        }
    }

    @Nested
    @DisplayName("Logical ID")
    inner class LogicalIdTests {
        @Test
        fun `id uses logical name from @OutboxHandler(name) on method`() {
            val handler = HandlerWithMethodAnnotation()
            val method = HandlerWithMethodAnnotation::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.id).isEqualTo("orders.process")
        }

        @Test
        fun `fqcnId still reflects FQCN even when logical id is set`() {
            val handler = HandlerWithMethodAnnotation()
            val method = HandlerWithMethodAnnotation::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.fqcnId).contains("HandlerWithMethodAnnotation#handle")
            assertThat(typedHandler.fqcnId).isNotEqualTo(typedHandler.id)
        }

        @Test
        fun `id falls back to fqcnId when no annotation is present`() {
            val handler = HandlerWithNoAnnotation()
            val method = HandlerWithNoAnnotation::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.id).isEqualTo(typedHandler.fqcnId)
            assertThat(typedHandler.id).contains("HandlerWithNoAnnotation#handle")
        }

        @Test
        fun `id uses value from @OutboxHandlerId on class`() {
            val handler = HandlerWithClassAnnotation()
            val method = HandlerWithClassAnnotation::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.id).isEqualTo("orders.typed")
        }

        @Test
        fun `explicitAliases are populated from @OutboxHandler(aliases)`() {
            val handler = HandlerWithAliases()
            val method = HandlerWithAliases::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            assertThat(typedHandler.explicitAliases).containsExactly("com.old.Handler.handle")
        }

        @Test
        fun `invalid id with reserved character throws at construction`() {
            val handler = HandlerWithInvalidId()
            val method = HandlerWithInvalidId::class.java.getMethod("handle", String::class.java)

            assertThatThrownBy { TypedHandlerMethod(handler, method) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved characters")
        }

        @Test
        fun `setting both name and value throws at construction`() {
            val handler = HandlerWithBothNameAndValue()
            val method = HandlerWithBothNameAndValue::class.java.getMethod("handle", String::class.java)

            assertThatThrownBy { TypedHandlerMethod(handler, method) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("use either 'name' or 'value', not both")
        }
    }

    @Nested
    @DisplayName("invoke() accessibility edge cases")
    inner class AccessibilityEdgeCases {
        @Test
        fun `should invoke public handle method in package-private class`() {
            class PackagePrivateHandler {
                fun handle(payload: String) {
                    received = payload
                }

                var received: String? = null
            }

            val handler = PackagePrivateHandler()
            val method = handler::class.java.getMethod("handle", String::class.java)
            val typedHandler = TypedHandlerMethod(handler, method)

            typedHandler.invoke("edge-case", createMetadata())
            assertThat(handler.received).isEqualTo("edge-case")
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

    class HandlerWithMethodAnnotation {
        @OutboxHandler(name = "orders.process")
        fun handle(payload: String) {}
    }

    class HandlerWithNoAnnotation {
        fun handle(payload: String) {}
    }

    @OutboxHandlerId("orders.typed")
    class HandlerWithClassAnnotation {
        fun handle(payload: String) {}
    }

    class HandlerWithAliases {
        @OutboxHandler(name = "orders.new", aliases = ["com.old.Handler.handle"])
        fun handle(payload: String) {}
    }

    class HandlerWithInvalidId {
        @OutboxHandler(name = "bad#id")
        fun handle(payload: String) {}
    }

    class HandlerWithBothNameAndValue {
        @OutboxHandler(name = "orders.new", value = "orders.old")
        fun handle(payload: String) {}
    }
}
