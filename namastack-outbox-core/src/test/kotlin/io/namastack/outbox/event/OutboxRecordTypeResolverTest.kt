package io.namastack.outbox.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxRecordTypeResolver")
class OutboxRecordTypeResolverTest {
    private val registry = OutboxEventTypeRegistry()
    private val resolver = OutboxRecordTypeResolver(registry)

    @Nested
    @DisplayName("toRecordType")
    inner class ToRecordType {
        @Test
        fun `returns logical name when class is registered`() {
            registry.register("OrderEvent", String::class.java, emptyList())

            assertThat(resolver.toRecordType("any-string-payload")).isEqualTo("OrderEvent")
        }

        @Test
        fun `returns FQCN when class has no registry entry`() {
            assertThat(resolver.toRecordType("any-string-payload")).isEqualTo("java.lang.String")
        }
    }

    @Nested
    @DisplayName("resolveClass")
    inner class ResolveClass {
        @Test
        fun `returns class via registry for logical name`() {
            registry.register("OrderEvent", String::class.java, emptyList())

            assertThat(resolver.resolveClass("OrderEvent")).isEqualTo(String::class.java)
        }

        @Test
        fun `returns class via registry for alias`() {
            registry.register("OrderEvent", String::class.java, listOf("com.acme.old.OrderEvent"))

            assertThat(resolver.resolveClass("com.acme.old.OrderEvent")).isEqualTo(String::class.java)
        }

        @Test
        fun `falls back to classloader for FQCN not in registry`() {
            assertThat(resolver.resolveClass("java.lang.String")).isEqualTo(String::class.java)
        }

        @Test
        fun `throws IllegalStateException for completely unknown name`() {
            assertThatThrownBy { resolver.resolveClass("com.nonexistent.MissingEvent") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("com.nonexistent.MissingEvent")
        }
    }
}
