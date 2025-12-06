package io.namastack.outbox.handler

import io.mockk.spyk
import io.namastack.outbox.annotation.OutboxHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxHandlerBeanPostProcessor")
class OutboxHandlerBeanPostProcessorTest {
    private val registry = spyk(OutboxHandlerRegistry())
    private lateinit var beanPostProcessor: OutboxHandlerBeanPostProcessor

    @BeforeEach
    fun setUp() {
        beanPostProcessor = OutboxHandlerBeanPostProcessor(registry)
    }

    @Nested
    @DisplayName("postProcessAfterInitialization()")
    inner class PostProcessAfterInitializationTests {
        @Test
        fun `should return bean unchanged`() {
            val bean = Object()

            val result = beanPostProcessor.postProcessAfterInitialization(bean, "testBean")

            assertThat(result).isSameAs(bean)
        }

        @Test
        fun `should handle bean with no handlers gracefully`() {
            val bean = Object()

            val result = beanPostProcessor.postProcessAfterInitialization(bean, "emptyBean")

            assertThat(result).isSameAs(bean)
        }

        @Test
        fun `should process multiple beans sequentially`() {
            val bean1 = Object()
            val bean2 = Object()

            val result1 = beanPostProcessor.postProcessAfterInitialization(bean1, "bean1")
            val result2 = beanPostProcessor.postProcessAfterInitialization(bean2, "bean2")

            assertThat(result1).isSameAs(bean1)
            assertThat(result2).isSameAs(bean2)
        }
    }

    @Nested
    @DisplayName("Handler Discovery")
    inner class HandlerDiscoveryTests {
        @Test
        fun `should discover typed handlers from @OutboxHandler annotated methods`() {
            val bean = TypedHandlerBean()

            beanPostProcessor.postProcessAfterInitialization(bean, "typedHandler")

            // Verify typed handler was registered in registry
            val handlers = registry.getHandlersForPayloadType(String::class)
            assertThat(handlers).isNotEmpty()
        }

        @Test
        fun `should discover generic handlers from @OutboxHandler annotated methods`() {
            val bean = GenericHandlerBean()

            beanPostProcessor.postProcessAfterInitialization(bean, "genericHandler")

            // Verify generic handler was registered
            val genericHandlers = registry.getGenericHandlers()
            assertThat(genericHandlers).isNotEmpty()
        }

        @Test
        fun `should discover multiple handlers from same bean`() {
            val bean = MultiHandlerBean()

            beanPostProcessor.postProcessAfterInitialization(bean, "multiHandler")

            // Verify multiple handlers were registered
            val stringHandlers = registry.getHandlersForPayloadType(String::class)
            val intHandlers = registry.getHandlersForPayloadType(Int::class)

            assertThat(stringHandlers).isNotEmpty()
            assertThat(intHandlers).isNotEmpty()
        }
    }

    @Nested
    @DisplayName("Interface-based Handler Discovery")
    inner class InterfaceHandlerDiscoveryTests {
        @Test
        fun `should discover typed handlers from OutboxTypedHandler interface implementation`() {
            val bean = TypedHandlerInterfaceImpl()

            beanPostProcessor.postProcessAfterInitialization(bean, "typedInterfaceHandler")

            // Verify typed handler was registered
            val handlers = registry.getHandlersForPayloadType(String::class)
            assertThat(handlers).isNotEmpty()
        }
    }

    @Nested
    @DisplayName("BeanPostProcessor Lifecycle")
    inner class BeanPostProcessorLifecycleTests {
        @Test
        fun `should implement BeanPostProcessor interface`() {
            assertThat(
                beanPostProcessor,
            ).isInstanceOf(org.springframework.beans.factory.config.BeanPostProcessor::class.java)
        }

        @Test
        fun `should have postProcessBeforeInitialization method`() {
            val bean = Object()
            val result = beanPostProcessor.postProcessBeforeInitialization(bean, "test")
            assertThat(result).isSameAs(bean)
        }

        @Test
        fun `should not throw exception when processing unknown bean types`() {
            val bean = UnknownBeanType()

            val result = beanPostProcessor.postProcessAfterInitialization(bean, "unknownBean")

            assertThat(result).isSameAs(bean)
        }
    }

    // Test bean implementations
    class TypedHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        fun handleString(payload: String) {
            // Typed handler for String payload
        }
    }

    class GenericHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        fun handleAny(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // Generic handler for any payload
        }
    }

    class MultiHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        fun handleString(payload: String) {
            // Handler for String
        }

        @OutboxHandler
        @Suppress("unused")
        fun handleInt(payload: Int) {
            // Handler for Int
        }
    }

    class UnknownBeanType

    // Interface-based test beans
    class TypedHandlerInterfaceImpl : OutboxTypedHandler<String> {
        override fun handle(payload: String) {
            // Typed handler implementation for String
        }
    }
}
