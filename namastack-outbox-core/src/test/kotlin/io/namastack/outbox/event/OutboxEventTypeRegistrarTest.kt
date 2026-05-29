package io.namastack.outbox.event

import io.namastack.outbox.annotation.OutboxEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory

@DisplayName("OutboxEventTypeRegistrar")
class OutboxEventTypeRegistrarTest {
    private lateinit var registry: OutboxEventTypeRegistry
    private val beanFactory = DefaultListableBeanFactory()

    @BeforeEach
    fun setUp() {
        registry = OutboxEventTypeRegistry()
    }

    @Nested
    @DisplayName("package scanning via additionalPackages")
    inner class PackageScanningTests {
        @Test
        fun `registers annotated class with logical name`() {
            registrar(listOf(SCAN_PACKAGE)).postProcessBeanFactory(beanFactory)

            assertThat(registry.findLogicalName(FixtureOrderPlaced::class.java)).isEqualTo("test.OrderPlaced")
            assertThat(registry.resolveClass("test.OrderPlaced")).isEqualTo(FixtureOrderPlaced::class.java)
        }

        @Test
        fun `skips classes with blank name`() {
            registrar(listOf(SCAN_PACKAGE)).postProcessBeanFactory(beanFactory)

            assertThat(registry.findLogicalName(FixtureBlankName::class.java)).isNull()
        }

        @Test
        fun `registers all declared aliases`() {
            registrar(listOf(SCAN_PACKAGE)).postProcessBeanFactory(beanFactory)

            assertThat(registry.resolveClass("test.OrderCreated")).isEqualTo(FixtureOrderWithAliases::class.java)
            assertThat(registry.resolveClass("test.order.v1")).isEqualTo(FixtureOrderWithAliases::class.java)
            assertThat(registry.resolveClass("test.order.v2")).isEqualTo(FixtureOrderWithAliases::class.java)
        }

        @Test
        fun `does nothing when no packages are configured`() {
            registrar(emptyList()).postProcessBeanFactory(beanFactory)

            assertThat(registry.findLogicalName(FixtureOrderPlaced::class.java)).isNull()
        }
    }

    private fun registrar(additionalPackages: List<String>): OutboxEventTypeRegistrar =
        OutboxEventTypeRegistrar(registry, additionalPackages)

    companion object {
        private val SCAN_PACKAGE = OutboxEventTypeRegistrarTest::class.java.packageName
    }
}

// ---------------------------------------------------------------------------
// Fixture event classes — scanned by the registrar in the same package
// ---------------------------------------------------------------------------

@OutboxEvent(name = "test.OrderPlaced")
class FixtureOrderPlaced

@OutboxEvent(name = "")
class FixtureBlankName

@OutboxEvent(name = "test.OrderCreated", aliases = ["test.order.v1", "test.order.v2"])
class FixtureOrderWithAliases
