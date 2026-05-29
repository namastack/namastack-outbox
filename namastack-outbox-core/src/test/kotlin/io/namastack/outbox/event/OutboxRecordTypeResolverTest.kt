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

    @Nested
    @DisplayName("round-trip write→read with logical names")
    inner class RoundTripTests {
        @Test
        fun `write path returns logical name and read path resolves back to class`() {
            registry.register("payments.OrderCreated", OrderCreatedRoundTrip::class.java, emptyList())

            val written = resolver.toRecordType(OrderCreatedRoundTrip())
            val resolved = resolver.resolveClass(written)

            assertThat(written).isEqualTo("payments.OrderCreated")
            assertThat(resolved).isEqualTo(OrderCreatedRoundTrip::class.java)
        }

        @Test
        fun `alias on read resolves to same class as primary logical name`() {
            registry.register(
                "payments.OrderCreated",
                OrderCreatedRoundTrip::class.java,
                listOf("com.acme.old.OrderEvent"),
            )

            assertThat(resolver.resolveClass("com.acme.old.OrderEvent"))
                .isEqualTo(OrderCreatedRoundTrip::class.java)
        }
    }

    @Nested
    @DisplayName("sealed-hierarchy annotation semantics")
    inner class SealedHierarchyTests {
        @Test
        fun `subtype payload uses FQCN when only the sealed parent is registered`() {
            registry.register("payments.BaseEvent", SealedParent::class.java, emptyList())

            // toRecordType uses the concrete subtype's class — not the parent
            val recordType = resolver.toRecordType(SealedChild("x"))

            assertThat(recordType).isEqualTo(SealedChild::class.java.name)
            assertThat(recordType).doesNotContain("payments.BaseEvent")
        }
    }

    class OrderCreatedRoundTrip

    sealed class SealedParent

    data class SealedChild(val id: String) : SealedParent()
}
