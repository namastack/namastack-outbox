package io.namastack.outbox.handler.registry

import io.mockk.mockk
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OutboxFallbackHandlerRegistry")
class OutboxFallbackHandlerRegistryTest {
    private lateinit var registry: OutboxFallbackHandlerRegistry

    @BeforeEach
    fun setUp() {
        registry = OutboxFallbackHandlerRegistry()
    }

    @Test
    fun `registers fallback handler for handler ID`() {
        val handlerId = "handler-1"
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId, fallbackHandler)

        val result = registry.getByHandlerId(handlerId)
        assertThat(result).isSameAs(fallbackHandler)
    }

    @Test
    fun `registers multiple fallback handlers for different handler IDs`() {
        val handlerId1 = "handler-1"
        val handlerId2 = "handler-2"
        val fallbackHandler1 = mockk<OutboxFallbackHandlerMethod>()
        val fallbackHandler2 = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId1, fallbackHandler1)
        registry.register(handlerId2, fallbackHandler2)

        assertThat(registry.getByHandlerId(handlerId1)).isSameAs(fallbackHandler1)
        assertThat(registry.getByHandlerId(handlerId2)).isSameAs(fallbackHandler2)
    }

    @Test
    fun `returns null when no fallback handler registered for handler ID`() {
        val result = registry.getByHandlerId("non-existent-handler")

        assertThat(result).isNull()
    }

    @Test
    fun `throws IllegalStateException when registering duplicate fallback for same handler ID`() {
        val handlerId = "handler-1"
        val fallbackHandler1 = mockk<OutboxFallbackHandlerMethod>()
        val fallbackHandler2 = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId, fallbackHandler1)

        assertThatThrownBy {
            registry.register(handlerId, fallbackHandler2)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Multiple fallback handlers for handler ID detected")
            .hasMessageContaining(handlerId)
    }

    @Test
    fun `maintains 1 to 1 mapping between handler ID and fallback handler`() {
        val handlerId = "handler-1"
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId, fallbackHandler)

        val result1 = registry.getByHandlerId(handlerId)
        val result2 = registry.getByHandlerId(handlerId)

        assertThat(result1).isSameAs(fallbackHandler)
        assertThat(result2).isSameAs(fallbackHandler)
        assertThat(result1).isSameAs(result2)
    }

    @Test
    fun `returns null after registering fallback for different handler ID`() {
        val handlerId1 = "handler-1"
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId1, fallbackHandler)

        val result = registry.getByHandlerId("handler-2")
        assertThat(result).isNull()
    }

    @Test
    fun `allows registering same fallback handler instance for different handler IDs`() {
        val handlerId1 = "handler-1"
        val handlerId2 = "handler-2"
        val sameFallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        registry.register(handlerId1, sameFallbackHandler)
        registry.register(handlerId2, sameFallbackHandler)

        assertThat(registry.getByHandlerId(handlerId1)).isSameAs(sameFallbackHandler)
        assertThat(registry.getByHandlerId(handlerId2)).isSameAs(sameFallbackHandler)
    }
}
