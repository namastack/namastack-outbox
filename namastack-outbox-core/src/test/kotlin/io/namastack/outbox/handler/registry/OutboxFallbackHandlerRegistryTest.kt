package io.namastack.outbox.handler.registry

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.handler.assembly.HandlerRegistration
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OutboxFallbackHandlerRegistry")
class OutboxFallbackHandlerRegistryTest {
    private lateinit var handlerRegistry: OutboxHandlerRegistry
    private lateinit var registry: OutboxFallbackHandlerRegistry

    @BeforeEach
    fun setUp() {
        handlerRegistry = mockk()
        every { handlerRegistry.getRegistrationById(any()) } returns null
        registry = OutboxFallbackHandlerRegistry(handlerRegistry)
    }

    @Test
    fun `returns fallback from complete handler registration`() {
        val fallback = mockk<OutboxFallbackHandlerMethod>()
        every { handlerRegistry.getRegistrationById("handler-id") } returns registrationWith(fallback)

        assertThat(registry.getByHandlerId("handler-id")).isSameAs(fallback)
        assertThat(registry.existsByHandlerId("handler-id")).isTrue()
    }

    @Test
    fun `canonical ID and alias resolve the same fallback`() {
        val fallback = mockk<OutboxFallbackHandlerMethod>()
        val registration = registrationWith(fallback)
        every { handlerRegistry.getRegistrationById("stable-id") } returns registration
        every { handlerRegistry.getRegistrationById("legacy-id") } returns registration

        assertThat(registry.getByHandlerId("stable-id")).isSameAs(fallback)
        assertThat(registry.getByHandlerId("legacy-id")).isSameAs(fallback)
    }

    @Test
    fun `returns null and false when handler ID is unknown`() {
        assertThat(registry.getByHandlerId("unknown-id")).isNull()
        assertThat(registry.existsByHandlerId("unknown-id")).isFalse()
    }

    @Test
    fun `returns null and false when registration has no fallback`() {
        every { handlerRegistry.getRegistrationById("handler-id") } returns registrationWith(null)

        assertThat(registry.getByHandlerId("handler-id")).isNull()
        assertThat(registry.existsByHandlerId("handler-id")).isFalse()
    }

    private fun registrationWith(fallback: OutboxFallbackHandlerMethod?): HandlerRegistration =
        mockk {
            every { this@mockk.fallback } returns fallback
        }
}
