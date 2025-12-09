package io.namastack.outbox.handler

import io.mockk.spyk
import io.namastack.outbox.annotation.OutboxHandler
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.aop.framework.ProxyFactory

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
        @ParameterizedTest
        @MethodSource("io.namastack.outbox.handler.OutboxHandlerBeanPostProcessorTest#typedHandlerBeans")
        fun `should discover typed handlers from @OutboxHandler annotated methods`(bean: Any) {
            beanPostProcessor.postProcessAfterInitialization(bean, "typedHandler")

            // Verify typed handler was registered in registry
            val handlers = registry.getHandlersForPayloadType(String::class)
            assertThat(handlers).isNotEmpty()
        }

        @ParameterizedTest
        @MethodSource("io.namastack.outbox.handler.OutboxHandlerBeanPostProcessorTest#genericHandlerBeans")
        fun `should discover generic handlers from @OutboxHandler annotated methods`(bean: Any) {
            beanPostProcessor.postProcessAfterInitialization(bean, "genericHandler")

            // Verify generic handler was registered
            val genericHandlers = registry.getGenericHandlers()
            assertThat(genericHandlers).isNotEmpty()
        }

        //
        @ParameterizedTest
        @MethodSource("io.namastack.outbox.handler.OutboxHandlerBeanPostProcessorTest#multiHandlerBeans")
        fun `should discover multiple handlers from same bean`(bean: Any) {
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

        @Test
        fun `should discover typed handlers from OutboxTypedHandler interface implementation with proxy`() {
            val bean = TypedHandlerInterfaceImpl()
            val proxy = createProxy(bean)

            beanPostProcessor.postProcessAfterInitialization(proxy, "typedInterfaceHandler")

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

    companion object {
        @JvmStatic
        fun typedHandlerBeans() =
            listOf(
                beanWithProxy("Bean", TypedHandlerBean()),
                beanWithProxy("Inherited bean", TypedHandlerBeanInherited()),
                beanWithProxy("Overridden inherited bean", TypedHandlerBeanInheritedOverridden()),
                beanWithProxy("Interface bean", TypedHandlerBeanImplemented()),
                beanWithProxy("Overridden interface bean", TypedHandlerBeanImplementedOverridden()),
            ).flatten()

        @JvmStatic
        fun genericHandlerBeans() =
            listOf(
                beanWithProxy("Bean", GenericHandlerBean()),
                beanWithProxy("Inherited bean", GenericHandlerBeanInherited()),
                beanWithProxy("Overridden inherited bean", GenericHandlerBeanInheritedOverridden()),
                beanWithProxy("Interface bean", GenericHandlerBeanImplemented()),
                beanWithProxy("Overridden interface bean", GenericHandlerBeanImplementedOverridden()),
            ).flatten()

        @JvmStatic
        fun multiHandlerBeans() =
            listOf(
                beanWithProxy("Bean", MultiHandlerBean()),
                beanWithProxy("Inherited bean", MultiHandlerBeanInherited()),
                beanWithProxy("Overridden inherited bean", MultiHandlerBeanInheritedOverridden()),
                beanWithProxy("Interface bean", MultiHandlerBeanImplemented()),
                beanWithProxy("Overridden interface bean", MultiHandlerBeanImplementedOverridden()),
            ).flatten()

        private fun beanWithProxy(
            name: String,
            bean: Any,
        ) = listOf(
            Arguments.of(Named.of(name, bean)),
            Arguments.of(Named.of("$name Proxy", createProxy(bean))),
        )

        private fun createProxy(target: Any): Any {
            val proxyFactory = ProxyFactory(target)
            proxyFactory.addAdvice(CustomAdvice())
            return proxyFactory.proxy
        }
    }

    // Test bean implementations
    open class TypedHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        open fun handleString(payload: String) {
            // Typed handler for String payload
        }
    }

    open class TypedHandlerBeanInherited : TypedHandlerBean()

    open class TypedHandlerBeanInheritedOverridden : TypedHandlerBean() {
        override fun handleString(payload: String) {
            super.handleString(payload)
        }
    }

    interface TypedHandlerBeanInterface {
        @OutboxHandler
        fun handleString(payload: String) {
            // Typed handler for String payload in interface class
        }
    }

    open class TypedHandlerBeanImplemented : TypedHandlerBeanInterface

    open class TypedHandlerBeanImplementedOverridden : TypedHandlerBeanInterface {
        override fun handleString(payload: String) {
            // Typed handler for String payload in implemented class
        }
    }

    open class GenericHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        open fun handleAny(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // Generic handler for any payload
        }
    }

    open class GenericHandlerBeanInherited : GenericHandlerBean()

    open class GenericHandlerBeanInheritedOverridden : GenericHandlerBean() {
        override fun handleAny(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            super.handleAny(payload, metadata)
        }
    }

    interface GenericHandlerBeanInterface {
        @OutboxHandler
        fun handleAny(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // Generic handler for any payload in interface class
        }
    }

    open class GenericHandlerBeanImplemented : GenericHandlerBeanInterface

    open class GenericHandlerBeanImplementedOverridden : GenericHandlerBeanInterface {
        override fun handleAny(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // Generic handler for any payload in implemented class
        }
    }

    open class MultiHandlerBean {
        @OutboxHandler
        @Suppress("unused")
        open fun handleString(payload: String) {
            // Handler for String
        }

        @OutboxHandler
        @Suppress("unused")
        open fun handleInt(payload: Int) {
            // Handler for Int
        }
    }

    open class MultiHandlerBeanInherited : MultiHandlerBean()

    open class MultiHandlerBeanInheritedOverridden : MultiHandlerBean() {
        override fun handleString(payload: String) {
            super.handleString(payload)
        }

        override fun handleInt(payload: Int) {
            super.handleInt(payload)
        }
    }

    interface MultiHandlerBeanInterfaceA {
        @OutboxHandler
        fun handleString(payload: String) {
            // Typed handler for String payload in interface class
        }
    }

    interface MultiHandlerBeanInterfaceB {
        @OutboxHandler
        fun handleInt(payload: Int) {
            // Typed handler for Int payload in interface class
        }
    }

    open class MultiHandlerBeanImplemented :
        MultiHandlerBeanInterfaceA,
        MultiHandlerBeanInterfaceB

    open class MultiHandlerBeanImplementedOverridden :
        MultiHandlerBeanInterfaceA,
        MultiHandlerBeanInterfaceB {
        override fun handleString(payload: String) {
            // Typed handler for String payload in implemented class
        }

        override fun handleInt(payload: Int) {
            // Typed handler for String payload in implemented class
        }
    }

    class UnknownBeanType

    // Interface-based test beans
    class TypedHandlerInterfaceImpl : OutboxTypedHandler<String> {
        override fun handle(payload: String) {
            // Typed handler implementation for String
        }
    }

    class CustomAdvice : MethodInterceptor {
        override fun invoke(invocation: MethodInvocation) = invocation.proceed()
    }
}
