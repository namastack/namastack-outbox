package io.namastack.outbox.retry

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import java.time.Duration

@DisplayName("OutboxRetryPolicyRegistry")
class OutboxRetryPolicyRegistryTest {
    private lateinit var beanFactory: BeanFactory
    private lateinit var defaultRetryPolicy: OutboxRetryPolicy
    private lateinit var registry: OutboxRetryPolicyRegistry

    @BeforeEach
    fun setUp() {
        defaultRetryPolicy = createMockRetryPolicy("default-policy")
        beanFactory =
            mockk<ListableBeanFactory> {
                every { getBean<OutboxRetryPolicy>("outboxRetryPolicy") } returns defaultRetryPolicy
            }
        registry = OutboxRetryPolicyRegistry(beanFactory)
    }

    @Nested
    @DisplayName("register()")
    inner class RegisterTests {
        @Test
        fun `should register retry policy for handler ID`() {
            val policy = createMockRetryPolicy("custom-policy")

            registry.register("handler-1", policy)

            assertThat(registry.getByHandlerId("handler-1")).isEqualTo(policy)
        }

        @Test
        fun `should allow overwriting policy for same handler ID`() {
            val policy1 = createMockRetryPolicy("policy-1")
            val policy2 = createMockRetryPolicy("policy-2")

            registry.register("handler-1", policy1)
            registry.register("handler-1", policy2)

            assertThat(registry.getByHandlerId("handler-1")).isEqualTo(policy2)
        }

        @Test
        fun `should register multiple policies for different handler IDs`() {
            val policy1 = createMockRetryPolicy("policy-1")
            val policy2 = createMockRetryPolicy("policy-2")

            registry.register("handler-1", policy1)
            registry.register("handler-2", policy2)

            assertThat(registry.getByHandlerId("handler-1")).isEqualTo(policy1)
            assertThat(registry.getByHandlerId("handler-2")).isEqualTo(policy2)
        }
    }

    @Nested
    @DisplayName("getByHandlerId()")
    inner class GetByHandlerIdTests {
        @Test
        fun `should return registered policy for handler ID`() {
            val policy = createMockRetryPolicy("custom-policy")
            registry.register("handler-1", policy)

            val result = registry.getByHandlerId("handler-1")

            assertThat(result).isEqualTo(policy)
        }

        @Test
        fun `should return default policy for unregistered handler ID`() {
            val result = registry.getByHandlerId("non-existent-handler")

            assertThat(result).isEqualTo(defaultRetryPolicy)
        }

        @Test
        fun `should return default policy when no policies are registered`() {
            val result = registry.getByHandlerId("any-handler")

            assertThat(result).isEqualTo(defaultRetryPolicy)
        }
    }

    @Nested
    @DisplayName("getDefaultRetryPolicy()")
    inner class GetDefaultRetryPolicyTests {
        @Test
        fun `should return default retry policy`() {
            val result = registry.getByHandlerId("any-handler")

            assertThat(result).isEqualTo(defaultRetryPolicy)
        }
    }

    @Nested
    @DisplayName("getRetryPolicy() by name")
    inner class GetRetryPolicyByNameTests {
        @Test
        fun `should load policy bean by name from BeanFactory`() {
            val customPolicy = createMockRetryPolicy("custom-policy")
            every { beanFactory.getBean<OutboxRetryPolicy>("aggressiveRetryPolicy") } returns customPolicy

            val result = registry.getRetryPolicy("aggressiveRetryPolicy")

            assertThat(result).isEqualTo(customPolicy)
        }

        @Test
        fun `should throw exception when bean not found by name`() {
            val beanFactory = mockk<ListableBeanFactory>()
            every { beanFactory.getBean<OutboxRetryPolicy>("unknownPolicy") } throws
                NoSuchBeanDefinitionException("unknownPolicy")
            every { beanFactory.getBeansOfType<OutboxRetryPolicy>() } returns
                mapOf(
                    "policy1" to createMockRetryPolicy("p1"),
                    "policy2" to createMockRetryPolicy("p2"),
                )

            val registry = OutboxRetryPolicyRegistry(beanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy("unknownPolicy")
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Retry policy bean 'unknownPolicy' not found")
                .hasMessageContaining("Available: [policy1, policy2]")
        }

        @Test
        fun `should throw exception with empty list when no policies available`() {
            val beanFactory = mockk<ListableBeanFactory>()
            every { beanFactory.getBean<OutboxRetryPolicy>("unknownPolicy") } throws
                NoSuchBeanDefinitionException("unknownPolicy")
            every { beanFactory.getBeansOfType<OutboxRetryPolicy>() } returns emptyMap()

            val registry = OutboxRetryPolicyRegistry(beanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy("unknownPolicy")
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Available: []")
        }

        @Test
        fun `should handle non-ListableBeanFactory gracefully`() {
            val simpleBeanFactory = mockk<BeanFactory>(relaxed = true)
            every { simpleBeanFactory.getBean<OutboxRetryPolicy>("unknownPolicy") } throws
                NoSuchBeanDefinitionException("unknownPolicy")

            val registry = OutboxRetryPolicyRegistry(simpleBeanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy("unknownPolicy")
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Retry policy bean 'unknownPolicy' not found")
        }
    }

    @Nested
    @DisplayName("getRetryPolicy() by class")
    inner class GetRetryPolicyByClassTests {
        @Test
        fun `should load policy bean by class from BeanFactory`() {
            val customPolicy = AggressiveRetryPolicy()
            every { beanFactory.getBean(AggressiveRetryPolicy::class.java) } returns customPolicy

            val result = registry.getRetryPolicy(AggressiveRetryPolicy::class)

            assertThat(result).isEqualTo(customPolicy)
        }

        @Test
        fun `should throw exception when bean not found by class`() {
            val beanFactory = mockk<ListableBeanFactory>()
            every { beanFactory.getBean(AggressiveRetryPolicy::class.java) } throws
                NoSuchBeanDefinitionException(AggressiveRetryPolicy::class.java, "No bean found")
            every { beanFactory.getBeansOfType<OutboxRetryPolicy>() } returns
                mapOf(
                    "gentlePolicy" to GentleRetryPolicy(),
                    "defaultPolicy" to createMockRetryPolicy("default"),
                )

            val registry = OutboxRetryPolicyRegistry(beanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy(AggressiveRetryPolicy::class)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Retry policy bean of type 'AggressiveRetryPolicy' not found")
                .hasMessageContaining("Available:")
                .hasMessageContaining("gentlePolicy (GentleRetryPolicy)")
        }

        @Test
        fun `should throw exception when multiple beans of same type exist`() {
            val beanFactory = mockk<BeanFactory>()
            every { beanFactory.getBean(AggressiveRetryPolicy::class.java) } throws
                IllegalStateException("Multiple beans found")

            val registry = OutboxRetryPolicyRegistry(beanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy(AggressiveRetryPolicy::class)
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `should handle non-ListableBeanFactory for class lookup`() {
            val simpleBeanFactory = mockk<BeanFactory>(relaxed = true)
            every { simpleBeanFactory.getBean(AggressiveRetryPolicy::class.java) } throws
                NoSuchBeanDefinitionException(AggressiveRetryPolicy::class.java, "Not found")

            val registry = OutboxRetryPolicyRegistry(simpleBeanFactory)

            assertThatThrownBy {
                registry.getRetryPolicy(AggressiveRetryPolicy::class)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Retry policy bean of type 'AggressiveRetryPolicy' not found")
        }
    }

    @Nested
    @DisplayName("getRetryPolicyForHandler()")
    inner class GetRetryPolicyForHandlerTests {
        @Test
        fun `should return registered policy for handler`() {
            val policy = createMockRetryPolicy("custom-policy")
            registry.register("handler-1", policy)

            val result = registry.getByHandlerId("handler-1")

            assertThat(result).isEqualTo(policy)
        }

        @Test
        fun `should return default policy for unregistered handler`() {
            val result = registry.getByHandlerId("unregistered-handler")

            assertThat(result).isEqualTo(defaultRetryPolicy)
        }
    }

    // Helper functions
    private fun createMockRetryPolicy(name: String): OutboxRetryPolicy =
        mockk<OutboxRetryPolicy>(name = name) {
            every { shouldRetry(any()) } returns true
            every { nextDelay(any()) } returns Duration.ofSeconds(1)
        }

    // Test policy implementations
    class AggressiveRetryPolicy : OutboxRetryPolicy {
        override fun shouldRetry(exception: Throwable): Boolean = true

        override fun nextDelay(failureCount: Int): Duration = Duration.ofSeconds((failureCount * 3).toLong())

        override fun maxRetries() = 3
    }

    class GentleRetryPolicy : OutboxRetryPolicy {
        override fun shouldRetry(exception: Throwable): Boolean = true

        override fun nextDelay(failureCount: Int): Duration = Duration.ofMinutes(failureCount.toLong())

        override fun maxRetries() = 3
    }
}
