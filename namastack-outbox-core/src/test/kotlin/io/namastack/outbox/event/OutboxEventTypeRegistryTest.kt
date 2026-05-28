package io.namastack.outbox.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxEventTypeRegistry")
class OutboxEventTypeRegistryTest {
    private lateinit var registry: OutboxEventTypeRegistry

    @BeforeEach
    fun setUp() {
        registry = OutboxEventTypeRegistry()
    }

    @Nested
    @DisplayName("findLogicalName")
    inner class FindLogicalName {
        @Test
        fun `returns logical name for registered class`() {
            registry.register("OrderEvent", String::class.java, emptyList())

            assertThat(registry.findLogicalName(String::class.java)).isEqualTo("OrderEvent")
        }

        @Test
        fun `returns null for unregistered class`() {
            assertThat(registry.findLogicalName(String::class.java)).isNull()
        }
    }

    @Nested
    @DisplayName("resolveClass")
    inner class ResolveClass {
        @Test
        fun `returns class for registered logical name`() {
            registry.register("OrderEvent", String::class.java, emptyList())

            assertThat(registry.resolveClass("OrderEvent")).isEqualTo(String::class.java)
        }

        @Test
        fun `returns class for registered alias`() {
            registry.register("OrderEvent", String::class.java, listOf("com.acme.old.OrderEvent"))

            assertThat(registry.resolveClass("com.acme.old.OrderEvent")).isEqualTo(String::class.java)
        }

        @Test
        fun `returns null for unknown name`() {
            assertThat(registry.resolveClass("Unknown")).isNull()
        }
    }

    @Nested
    @DisplayName("duplicate detection")
    inner class DuplicateDetection {
        @Test
        fun `registering same logical name for two different classes throws`() {
            registry.register("Foo", String::class.java, emptyList())

            assertThatThrownBy { registry.register("Foo", Int::class.java, emptyList()) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Foo")
                .hasMessageContaining(String::class.java.name)
                .hasMessageContaining(Int::class.java.name)
        }

        @Test
        fun `registering same logical name for same class is idempotent`() {
            registry.register("Foo", String::class.java, emptyList())
            registry.register("Foo", String::class.java, emptyList())

            assertThat(registry.resolveClass("Foo")).isEqualTo(String::class.java)
        }

        @Test
        fun `alias that collides with another class primary name throws`() {
            registry.register("Foo", String::class.java, emptyList())

            assertThatThrownBy { registry.register("Bar", Int::class.java, listOf("Foo")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Foo")
        }
    }

    @Nested
    @DisplayName("multiple aliases")
    inner class MultipleAliases {
        @Test
        fun `all aliases resolve to the same class`() {
            registry.register(
                "OrderEvent",
                String::class.java,
                listOf("com.acme.v1.OrderEvent", "com.acme.v2.OrderEvent"),
            )

            assertThat(registry.resolveClass("OrderEvent")).isEqualTo(String::class.java)
            assertThat(registry.resolveClass("com.acme.v1.OrderEvent")).isEqualTo(String::class.java)
            assertThat(registry.resolveClass("com.acme.v2.OrderEvent")).isEqualTo(String::class.java)
        }
    }
}
