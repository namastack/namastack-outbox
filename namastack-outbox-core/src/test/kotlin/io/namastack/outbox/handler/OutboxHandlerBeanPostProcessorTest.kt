package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.CustomerOutboxRetryPolicy
import io.namastack.outbox.HandlerBeanFactory
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.aopalliance.intercept.MethodInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import kotlin.reflect.KClass

@DisplayName("OutboxHandlerBeanPostProcessor")
class OutboxHandlerBeanPostProcessorTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>(relaxed = true)
    private val retryPolicyRegistry = mockk<OutboxRetryPolicyRegistry>(relaxed = true)

    private lateinit var beanPostProcessor: OutboxHandlerBeanPostProcessor

    @BeforeEach
    fun setUp() {
        beanPostProcessor =
            OutboxHandlerBeanPostProcessor(handlerRegistry, retryPolicyRegistry)
    }

    @Test
    fun `does nothing when no handlers found and just returns bean`() {
        val bean = mockk<Any>()
        val result = beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 0) { handlerRegistry.registerBatch(any()) }

        assertThat(result).isEqualTo(bean)
    }

    @Test
    fun `registers typed handler bean when implementing OutboxTypedHandler`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().primary is TypedHandlerMethod }) }
    }

    @Test
    fun `registers generic handler bean when implementing OutboxHandler`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `does not register generated lambda ID as legacy alias for non-proxied lambda`() {
        val realHandlerRegistry = OutboxHandlerRegistry()
        val processor =
            OutboxHandlerBeanPostProcessor(
                realHandlerRegistry,
                retryPolicyRegistry,
            )
        val lambda = LambdaOutboxHandlerFactory.create()

        processor.postProcessAfterInitialization(lambda, "modulithEventExternalizer")

        val handler = requireNotNull(realHandlerRegistry.getHandlerById("modulithEventExternalizer"))
        assertThat(realHandlerRegistry.getHandlerById(runtimeGeneratedId(handler))).isNull()
    }

    @Test
    fun `does not register generated proxy ID as legacy alias for proxied lambda`() {
        val realHandlerRegistry = OutboxHandlerRegistry()
        val processor =
            OutboxHandlerBeanPostProcessor(
                realHandlerRegistry,
                retryPolicyRegistry,
            )
        val proxyFactory = ProxyFactory(LambdaOutboxHandlerFactory.create())
        proxyFactory.addAdvice(MethodInterceptor { it.proceed() })
        val proxiedLambda = proxyFactory.proxy

        processor.postProcessAfterInitialization(proxiedLambda, "modulithEventExternalizer")

        val handler = requireNotNull(realHandlerRegistry.getHandlerById("modulithEventExternalizer"))
        assertThat(realHandlerRegistry.getHandlerById(runtimeGeneratedId(handler))).isNull()
    }

    @Test
    fun `registers typed handler bean when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().primary is TypedHandlerMethod }) }
    }

    @Test
    fun `registers typed handler bean without metadata when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithoutMetadata()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().primary is TypedHandlerMethod }) }
    }

    @Test
    fun `registers generic handler when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedGenericHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `registers handler when package-private method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createJavaPackagePrivateAnnotatedHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `registers handler with fallback when package-private @OutboxHandler and @OutboxFallbackHandler methods`() {
        val bean = HandlerBeanFactory.createJavaPackagePrivateAnnotatedHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `registers handler with retry policy when package-private @OutboxHandler and @OutboxRetryable method`() {
        every { retryPolicyRegistry.getRetryPolicy(any<String>()) } returns CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createJavaPackagePrivateAnnotatedHandlerWithRetryable()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers multiple handlers from same bean`() {
        val bean = HandlerBeanFactory.createMultiAnnotatedHandlerBean()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 2 }) }
    }

    @Test
    fun `registers typed handler with fallback when implementing OutboxTypedHandlerWithFallback`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `registers generic handler with fallback when implementing OutboxHandlerWithFallback`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `registers typed handler with fallback when annotated with OutboxFallbackHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `registers multiple typed handlers with one fallback for each`() {
        val bean = HandlerBeanFactory.createMultipleAnnotatedTypedHandlersWithMultipleFallbacks()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) {
            handlerRegistry.registerBatch(
                match { registrations ->
                    registrations.size == 2 && registrations.all { it.fallback != null }
                },
            )
        }
    }

    @Test
    fun `does not register fallback when fallback signature does not match`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithNonMatchingFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `does not register generic fallback for typed handler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithGenericFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `registers generic handler with fallback when annotated with OutboxFallbackHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedGenericHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `does not register fallback when fallback signature invalid`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithInvalidFallbackSignature()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `registers handlers from inherited class`() {
        val bean = HandlerBeanFactory.createInheritedHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.size == 1 }) }
    }

    @Test
    fun `does not register annotated handler method with wrong signature`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithWrongSignature()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 0) { handlerRegistry.registerBatch(any()) }
    }

    @Test
    fun `registers only one fallback handler when multiple match for the same handler`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithMultipleMatchingFallbacks()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().fallback != null }) }
    }

    @Test
    fun `registers generic handler with retry policy when implementing OutboxRetryAware`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandlerWithRetryPolicy()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers typed handler with retry policy when implementing OutboxRetryAware`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandlerWithRetryPolicy()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers generic handler with retry policy when annotated with OutboxRetryAware and class ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<KClass<out OutboxRetryPolicy>>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createGenericAnnotatedHandlerWithRetryPolicyByClass()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers typed handler with retry policy when annotated with OutboxRetryAware and class ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<KClass<out OutboxRetryPolicy>>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createTypedAnnotatedHandlerWithRetryPolicyByClass()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers generic handler with retry policy when annotated with OutboxRetryAware and name ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<String>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createGenericAnnotatedHandlerWithRetryPolicyByName()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Test
    fun `registers typed handler with retry policy when annotated with OutboxRetryAware and name ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<String>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createTypedAnnotatedHandlerWithRetryPolicyByName()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.registerBatch(match { it.single().explicitRetryPolicy != null }) }
    }

    @Nested
    @DisplayName("Legacy alias registration for CGLIB proxies")
    inner class LegacyAliasTests {
        private val realHandlerRegistry = OutboxHandlerRegistry()
        private val realFallbackRegistry = OutboxFallbackHandlerRegistry(realHandlerRegistry)

        private lateinit var proxyProcessor: OutboxHandlerBeanPostProcessor

        @BeforeEach
        fun setUp() {
            proxyProcessor =
                OutboxHandlerBeanPostProcessor(realHandlerRegistry, retryPolicyRegistry)
        }

        @Test
        fun `registers legacy alias when bean is a CGLIB proxy`() {
            val targetBean = OpenAnnotatedTypedHandler()
            val proxiedBean = createCglibProxy(targetBean)

            proxyProcessor.postProcessAfterInitialization(proxiedBean, "bean")

            // Stable ID (target class name) should work
            val handler = realHandlerRegistry.getHandlersForPayloadType(String::class).first()
            val runtimeGeneratedId = runtimeGeneratedId(handler)
            assertThat(realHandlerRegistry.getHandlerById(handler.id)).isNotNull

            // Legacy ID (proxy class name) should also work
            assertThat(runtimeGeneratedId).isNotEqualTo(handler.id)
            assertThat(realHandlerRegistry.getHandlerById(runtimeGeneratedId)).isNotNull

            // Both should resolve to the same handler
            assertThat(realHandlerRegistry.getHandlerById(handler.id))
                .isSameAs(realHandlerRegistry.getHandlerById(runtimeGeneratedId))
        }

        @Test
        fun `does not register legacy alias when bean is not proxied`() {
            val bean = OpenAnnotatedTypedHandler()

            proxyProcessor.postProcessAfterInitialization(bean, "bean")

            val stableId = realHandlerRegistry.getHandlersForPayloadType(String::class).first().id
            assertThat(stableId).doesNotContain("\$\$SpringCGLIB\$\$")

            // Only the stable ID should be registered (no alias needed)
            assertThat(realHandlerRegistry.getHandlerById(stableId)).isNotNull
        }

        @Test
        fun `registers legacy alias for fallback handler when bean is a CGLIB proxy`() {
            val targetBean = OpenAnnotatedTypedHandlerWithFallback()
            val proxiedBean = createCglibProxy(targetBean)

            proxyProcessor.postProcessAfterInitialization(proxiedBean, "bean")

            val handler = realHandlerRegistry.getHandlersForPayloadType(String::class).first()
            val runtimeGeneratedId = runtimeGeneratedId(handler)
            assertThat(runtimeGeneratedId).isNotEqualTo(handler.id)

            // Both IDs should resolve to the same fallback handler
            assertThat(realFallbackRegistry.getByHandlerId(handler.id)).isNotNull
            assertThat(realFallbackRegistry.getByHandlerId(runtimeGeneratedId)).isNotNull
            assertThat(realFallbackRegistry.getByHandlerId(handler.id))
                .isSameAs(realFallbackRegistry.getByHandlerId(runtimeGeneratedId))
        }

        private fun createCglibProxy(target: Any): Any {
            val proxyFactory = ProxyFactory(target)
            proxyFactory.isProxyTargetClass = true
            proxyFactory.addAdvice(MethodInterceptor { it.proceed() })
            return proxyFactory.proxy
        }
    }

    private fun runtimeGeneratedId(handler: OutboxHandlerMethod): String =
        OutboxHandlerMethod.generatedId(handler.bean, handler.method, handler.bean::class.java)
}
