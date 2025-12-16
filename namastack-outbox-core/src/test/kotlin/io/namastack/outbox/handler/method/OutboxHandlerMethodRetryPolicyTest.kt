package io.namastack.outbox.handler.method

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanFactory
import java.time.Duration

@DisplayName("OutboxHandlerMethod - registerRetryPolicy()")
class OutboxHandlerMethodRetryPolicyTest {
    @Test
    fun `should register policy from @OutboxRetryable with class`() {
        val customPolicy = TestRetryPolicy()
        val beanFactory =
            mockk<BeanFactory> {
                every { getBean(TestRetryPolicy::class.java) } returns customPolicy
            }
        val registry = createRegistry(beanFactory)

        val handler = createHandler(HandlerWithClassAnnotation())
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(customPolicy)
    }

    @Test
    fun `should register policy from @OutboxRetryable with name`() {
        val customPolicy = TestRetryPolicy()
        val beanFactory =
            mockk<BeanFactory> {
                every { getBean("customPolicy", OutboxRetryPolicy::class.java) } returns customPolicy
            }
        val registry = createRegistry(beanFactory)

        val handler = createHandler(HandlerWithNameAnnotation())
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(customPolicy)
    }

    @Test
    fun `should prefer class over name in @OutboxRetryable`() {
        val classPolicy = TestRetryPolicy()
        val namePolicy = mockk<OutboxRetryPolicy>()
        val beanFactory =
            mockk<BeanFactory> {
                every { getBean(TestRetryPolicy::class.java) } returns classPolicy
                every { getBean("namePolicy", OutboxRetryPolicy::class.java) } returns namePolicy
            }
        val registry = createRegistry(beanFactory)

        val handler = createHandler(HandlerWithBothClassAndName())
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(classPolicy)
    }

    @Test
    fun `should register policy from OutboxRetryAware interface`() {
        val customPolicy = TestRetryPolicy()
        val registry = createRegistry()

        val handler = createHandler(HandlerWithRetryAware(customPolicy))
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(customPolicy)
    }

    @Test
    fun `should prefer annotation over OutboxRetryAware interface`() {
        val annotationPolicy = TestRetryPolicy()
        val interfacePolicy = mockk<OutboxRetryPolicy>()
        val beanFactory =
            mockk<BeanFactory> {
                every { getBean("annotationPolicy", OutboxRetryPolicy::class.java) } returns annotationPolicy
            }
        val registry = createRegistry(beanFactory)

        val handler = createHandler(HandlerWithBothAnnotationAndInterface(interfacePolicy))
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(annotationPolicy)
    }

    @Test
    fun `should use default policy when no configuration`() {
        val defaultPolicy = TestRetryPolicy()
        val registry = createRegistry(defaultPolicy = defaultPolicy)

        val handler = createHandler(HandlerWithoutConfiguration())
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(defaultPolicy)
    }

    @Test
    fun `should use default policy when annotation is empty`() {
        val defaultPolicy = TestRetryPolicy()
        val registry = createRegistry(defaultPolicy = defaultPolicy)

        val handler = createHandler(HandlerWithEmptyAnnotation())
        handler.registerRetryPolicy(registry)

        val registered = registry.getByHandlerId(handler.id)
        assertThat(registered).isEqualTo(defaultPolicy)
    }

    // Helper methods
    private fun createHandler(bean: Any): TypedHandlerMethod {
        val method = bean::class.java.methods.first { it.name == "handle" }
        return TypedHandlerMethod(bean, method, String::class)
    }

    private fun createRegistry(
        beanFactory: BeanFactory = mockk(relaxed = true),
        defaultPolicy: OutboxRetryPolicy = mockk(relaxed = true),
    ): OutboxRetryPolicyRegistry = OutboxRetryPolicyRegistry(beanFactory, defaultPolicy)

    // Test policy
    class TestRetryPolicy : OutboxRetryPolicy {
        override fun shouldRetry(exception: Throwable) = true

        override fun nextDelay(failureCount: Int) = Duration.ofSeconds(1)

        override fun maxRetries() = 3
    }

    // Test handlers
    class HandlerWithClassAnnotation {
        @OutboxRetryable(TestRetryPolicy::class)
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithNameAnnotation {
        @OutboxRetryable(name = "customPolicy")
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithBothClassAndName {
        @OutboxRetryable(value = TestRetryPolicy::class, name = "namePolicy")
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithRetryAware(
        private val policy: OutboxRetryPolicy,
    ) : OutboxRetryAware {
        override fun getRetryPolicy() = policy

        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithBothAnnotationAndInterface(
        private val policy: OutboxRetryPolicy,
    ) : OutboxRetryAware {
        override fun getRetryPolicy() = policy

        @OutboxRetryable(name = "annotationPolicy")
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithoutConfiguration {
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }

    class HandlerWithEmptyAnnotation {
        @OutboxRetryable
        fun handle(
            @Suppress("UNUSED_PARAMETER") p: String,
        ) = Unit
    }
}
