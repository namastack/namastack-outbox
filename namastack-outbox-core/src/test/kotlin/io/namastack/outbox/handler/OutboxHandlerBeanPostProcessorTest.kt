package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.CustomerOutboxRetryPolicy
import io.namastack.outbox.HandlerBeanFactory
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

@DisplayName("OutboxHandlerBeanPostProcessor")
class OutboxHandlerBeanPostProcessorTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>(relaxed = true)
    private val fallbackHandlerRegistry = mockk<OutboxFallbackHandlerRegistry>(relaxed = true)
    private val retryPolicyRegistry = mockk<OutboxRetryPolicyRegistry>(relaxed = true)

    private lateinit var beanPostProcessor: OutboxHandlerBeanPostProcessor

    @BeforeEach
    fun setUp() {
        beanPostProcessor =
            OutboxHandlerBeanPostProcessor(handlerRegistry, fallbackHandlerRegistry, retryPolicyRegistry)
    }

    @Test
    fun `does nothing when no handlers found and just returns bean`() {
        val bean = mockk<Any>()
        val result = beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 0) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }

        assertThat(result).isEqualTo(bean)
    }

    @Test
    fun `registers typed handler bean when implementing OutboxTypedHandler`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any(TypedHandlerMethod::class)) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler bean when implementing OutboxHandler`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler bean when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any(TypedHandlerMethod::class)) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler bean without metadata when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithoutMetadata()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any(TypedHandlerMethod::class)) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler when method annotated with @OutboxHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedGenericHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers multiple handlers from same bean`() {
        val bean = HandlerBeanFactory.createMultiAnnotatedHandlerBean()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 2) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler with fallback when implementing OutboxTypedHandlerWithFallback`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 1) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler with fallback when implementing OutboxHandlerWithFallback`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 1) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler with fallback when annotated with OutboxFallbackHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 1) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers multiple typed handlers with one fallback for each`() {
        val bean = HandlerBeanFactory.createMultipleAnnotatedTypedHandlersWithMultipleFallbacks()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 2) { handlerRegistry.register(any()) }
        verify(exactly = 2) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `does not register fallback when fallback signature does not match`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithNonMatchingFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `does not register generic fallback for typed handler`() {
        val bean = HandlerBeanFactory.createAnnotatedTypedHandlerWithGenericFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler with fallback when annotated with OutboxFallbackHandler`() {
        val bean = HandlerBeanFactory.createAnnotatedGenericHandlerWithFallback()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 1) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `does not register fallback when fallback signature invalid`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithInvalidFallbackSignature()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers handlers from inherited class`() {
        val bean = HandlerBeanFactory.createInheritedHandler()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `does not register annotated handler method with wrong signature`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithWrongSignature()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 0) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers only one fallback handler when multiple match for the same handler`() {
        val bean = HandlerBeanFactory.createAnnotatedHandlerBeanWithMultipleMatchingFallbacks()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 1) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 0) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler with retry policy when implementing OutboxRetryAware`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandlerWithRetryPolicy()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler with retry policy when implementing OutboxRetryAware`() {
        val bean = HandlerBeanFactory.createTypedInterfaceHandlerWithRetryPolicy()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler with retry policy when annotated with OutboxRetryAware and class ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<KClass<out OutboxRetryPolicy>>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createGenericAnnotatedHandlerWithRetryPolicyByClass()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler with retry policy when annotated with OutboxRetryAware and class ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<KClass<out OutboxRetryPolicy>>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createTypedAnnotatedHandlerWithRetryPolicyByClass()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers generic handler with retry policy when annotated with OutboxRetryAware and name ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<String>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createGenericAnnotatedHandlerWithRetryPolicyByName()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Test
    fun `registers typed handler with retry policy when annotated with OutboxRetryAware and name ref`() {
        every { retryPolicyRegistry.getRetryPolicy(any<String>()) } returns
            CustomerOutboxRetryPolicy()

        val bean = HandlerBeanFactory.createTypedAnnotatedHandlerWithRetryPolicyByName()
        beanPostProcessor.postProcessAfterInitialization(bean, "bean")

        verify(exactly = 1) { handlerRegistry.register(any()) }
        verify(exactly = 0) { fallbackHandlerRegistry.register(any(), any()) }
        verify(exactly = 1) { retryPolicyRegistry.register(any(), any()) }
    }

    @Nested
    @DisplayName("Legacy alias registration for CGLIB proxies")
    inner class LegacyAliasTests {
        private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
        private val realHandlerRegistry = OutboxHandlerRegistry()
        private val realFallbackRegistry = OutboxFallbackHandlerRegistry()

        private lateinit var proxyProcessor: OutboxHandlerBeanPostProcessor

        @BeforeEach
        fun setUp() {
            proxyProcessor =
                OutboxHandlerBeanPostProcessor(realHandlerRegistry, realFallbackRegistry, retryPolicyRegistry)
        }

        @Test
        fun `registers legacy alias when bean is a CGLIB proxy`() {
            val targetBean = OpenAnnotatedTypedHandler()
            val proxiedBean = createCglibProxy(targetBean)

            proxyProcessor.postProcessAfterInitialization(proxiedBean, "bean")

            // Stable ID (target class name) should work
            val handler = realHandlerRegistry.getHandlersForPayloadType(String::class).first()
            assertThat(realHandlerRegistry.getHandlerById(handler.id)).isNotNull

            // Legacy ID (proxy class name) should also work
            assertThat(handler.legacyId).isNotEqualTo(handler.id)
            assertThat(realHandlerRegistry.getHandlerById(handler.legacyId)).isNotNull

            // Both should resolve to the same handler
            assertThat(realHandlerRegistry.getHandlerById(handler.id))
                .isSameAs(realHandlerRegistry.getHandlerById(handler.legacyId))
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
            assertThat(handler.legacyId).isNotEqualTo(handler.id)

            // Both IDs should resolve to the same fallback handler
            assertThat(realFallbackRegistry.getByHandlerId(handler.id)).isNotNull
            assertThat(realFallbackRegistry.getByHandlerId(handler.legacyId)).isNotNull
            assertThat(realFallbackRegistry.getByHandlerId(handler.id))
                .isSameAs(realFallbackRegistry.getByHandlerId(handler.legacyId))
        }

        private fun createCglibProxy(target: Any): Any {
            val proxyFactory = ProxyFactory(target)
            proxyFactory.isProxyTargetClass = true
            proxyFactory.addAdvice(MethodInterceptor { it.proceed() })
            return proxyFactory.proxy
        }
    }
}

@Suppress("UNUSED_PARAMETER")
open class OpenAnnotatedTypedHandler {
    @io.namastack.outbox.annotation.OutboxHandler
    open fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
open class OpenAnnotatedTypedHandlerWithFallback {
    @io.namastack.outbox.annotation.OutboxHandler
    open fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @io.namastack.outbox.annotation.OutboxFallbackHandler
    open fun handleFailure(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }
}
