package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.annotation.OutboxEvent.OutboxContextEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal

@DisplayName("OutboxEventMulticaster")
class OutboxEventMulticasterTest {
    private var outbox = mockk<Outbox>(relaxed = true)
    private var outboxProperties = OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = true))
    private var delegateEventMulticaster = mockk<SimpleApplicationEventMulticaster>()

    private lateinit var eventMulticaster: OutboxEventMulticaster

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        eventMulticaster =
            OutboxEventMulticaster(
                outbox = outbox,
                outboxProperties = outboxProperties,
                delegateEventMulticaster = delegateEventMulticaster,
            )

        every { delegateEventMulticaster.multicastEvent(any(), any()) } answers { }
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    @Nested
    @DisplayName("Event Delegation")
    inner class EventDelegation {
        @Test
        fun `should pass non-annotated events to delegate multicaster`() {
            val event = PayloadApplicationEvent(this, NotAnnotatedEvent(id = "agg-1"))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) {
                delegateEventMulticaster.multicastEvent(event, any())
            }
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }

        @Test
        fun `should pass non-PayloadApplicationEvent to delegate multicaster`() {
            val event = ContextRefreshedEvent(mockk<ApplicationContext>())

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) {
                delegateEventMulticaster.multicastEvent(event, any())
            }
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("Outbox Persistence")
    inner class OutboxPersistence {
        @Test
        fun `should store annotated events in outbox when transaction is active`() {
            TransactionSynchronizationManager.setActualTransactionActive(true)

            val payload = AnnotatedEvent(id = "agg-1")
            val event = PayloadApplicationEvent(this, payload)

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(payload, "agg-1", emptyMap()) }
            verify(exactly = 1) {
                delegateEventMulticaster.multicastEvent(any<PayloadApplicationEvent<*>>(), any())
            }
        }

        @Test
        fun `should throw exception when no transaction is active`() {
            TransactionSynchronizationManager.setActualTransactionActive(false)

            val event = PayloadApplicationEvent(this, AnnotatedEvent(id = "agg-1"))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessage("OutboxEvent requires an active transaction")

            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
            verify(exactly = 0) { delegateEventMulticaster.multicastEvent(any(), any()) }
        }

        @Test
        fun `should not publish to delegate when publishAfterSave is false`() {
            val localProperties = OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = false))
            val localMulticaster =
                OutboxEventMulticaster(
                    outbox = outbox,
                    outboxProperties = localProperties,
                    delegateEventMulticaster = delegateEventMulticaster,
                )

            val payload = AnnotatedEvent(id = "agg-1")
            val event = PayloadApplicationEvent(this, payload)

            localMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(payload, "agg-1", emptyMap()) }
            verify(exactly = 0) {
                delegateEventMulticaster.multicastEvent(any(), any())
            }
        }

        @Test
        fun `should not publish to delegate when deprecated publishAfterSave is false`() {
            val localProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = false))
            val localMulticaster =
                OutboxEventMulticaster(
                    outbox = outbox,
                    outboxProperties = localProperties,
                    delegateEventMulticaster = delegateEventMulticaster,
                )

            val payload = AnnotatedEvent(id = "agg-1")
            val event = PayloadApplicationEvent(this, payload)

            localMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(payload, "agg-1", emptyMap()) }
            verify(exactly = 0) {
                delegateEventMulticaster.multicastEvent(any(), any())
            }
        }

        @Test
        fun `should publish to delegate when publishAfterSave is true`() {
            val payload = AnnotatedEvent(id = "agg-1")
            val event = PayloadApplicationEvent(this, payload)

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(payload, "agg-1", emptyMap()) }
            verify(exactly = 1) {
                delegateEventMulticaster.multicastEvent(event, any())
            }
        }
    }

    @Nested
    @DisplayName("Key Resolution")
    inner class KeyResolution {
        @Test
        fun `should resolve simple field key expression`() {
            val event = PayloadApplicationEvent(this, AnnotatedEvent(id = "agg-1"))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(any(), "agg-1", emptyMap()) }
        }

        @Test
        fun `should resolve SpEL this reference key expression`() {
            val event = PayloadApplicationEvent(this, ThisReferenceKeyEvent(customId = "custom-123"))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(any(), "custom-123", emptyMap()) }
        }

        @Test
        fun `should resolve SpEL root reference key expression`() {
            val event = PayloadApplicationEvent(this, RootReferenceKeyEvent(orderId = "order-456"))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(any(), "order-456", emptyMap()) }
        }

        @Test
        fun `should resolve nested property key expression`() {
            val address = Address(city = "Berlin", region = "EU")
            val event = PayloadApplicationEvent(this, NestedKeyEvent(id = "evt-1", address = address))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(any(), "EU", emptyMap()) }
        }

        @Test
        fun `should generate UUID when key annotation is empty`() {
            val event = PayloadApplicationEvent(this, EmptyKeyEvent(id = "agg-1"))

            eventMulticaster.multicastEvent(event)

            val keySlot = slot<String>()
            verify(exactly = 1) { outbox.schedule(any(), capture(keySlot), any()) }

            // Verify it's a UUID format (simple check)
            assertThat(keySlot.captured).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        }

        @Test
        fun `should generate unique UUIDs for different events with empty key`() {
            val event1 = PayloadApplicationEvent(this, EmptyKeyEvent(id = "agg-1"))
            val event2 = PayloadApplicationEvent(this, EmptyKeyEvent(id = "agg-2"))
            val keys = mutableListOf<String>()

            every { outbox.schedule(any(), any(), any()) } answers {
                keys.add(secondArg())
            }

            eventMulticaster.multicastEvent(event1)
            eventMulticaster.multicastEvent(event2)

            verify(exactly = 2) { outbox.schedule(any(), any(), any()) }
            assertThat(keys).hasSize(2)
            assertThat(keys[0]).isNotEqualTo(keys[1])
        }

        @Test
        fun `should throw exception when SpEL expression returns null`() {
            val event = PayloadApplicationEvent(this, NullableKeyEvent(id = "agg-1", optionalKey = null))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }

        @Test
        fun `should throw exception when SpEL expression returns non-string value`() {
            val event = PayloadApplicationEvent(this, NonStringKeyEvent(id = 42))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Failed to resolve value from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
        }

        @Test
        fun `should throw exception when SpEL expression is invalid`() {
            val event = PayloadApplicationEvent(this, InvalidSpELKeyEvent(id = "agg-1"))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }

        @Test
        fun `should throw exception when field does not exist`() {
            val event = PayloadApplicationEvent(this, NonExistentFieldKeyEvent(id = "agg-1"))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }
    }

    @Nested
    @DisplayName("Context Resolution")
    inner class ContextResolution {
        @Test
        fun `should resolve empty context when no context entries defined`() {
            val event = PayloadApplicationEvent(this, AnnotatedEvent(id = "agg-1"))

            eventMulticaster.multicastEvent(event)

            verify(exactly = 1) { outbox.schedule(any(), any(), emptyMap()) }
        }

        @Test
        fun `should resolve single context entry`() {
            val event =
                PayloadApplicationEvent(
                    this,
                    SingleContextEvent(userId = "user-123", email = "test@example.com"),
                )

            eventMulticaster.multicastEvent(event)

            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), any(), capture(contextSlot)) }

            assertThat(contextSlot.captured).containsEntry("email", "test@example.com")
        }

        @Test
        fun `should resolve multiple context entries`() {
            val event =
                PayloadApplicationEvent(
                    this,
                    MultipleContextEvent(orderId = "order-456", customerId = "cust-789", total = "99.99"),
                )

            eventMulticaster.multicastEvent(event)

            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), any(), capture(contextSlot)) }

            assertThat(contextSlot.captured)
                .containsEntry("customerId", "cust-789")
                .containsEntry("total", "99.99")
                .hasSize(2)
        }

        @Test
        fun `should resolve static string context values`() {
            val event = PayloadApplicationEvent(this, StaticContextEvent(id = "evt-1"))

            eventMulticaster.multicastEvent(event)

            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), any(), capture(contextSlot)) }

            assertThat(contextSlot.captured)
                .containsEntry("eventType", "STATIC_EVENT")
                .containsEntry("version", "1.0")
        }

        @Test
        fun `should resolve nested property in context`() {
            val address = Address(city = "Berlin", region = "EU")
            val event = PayloadApplicationEvent(this, NestedContextEvent(id = "evt-1", address = address))

            eventMulticaster.multicastEvent(event)

            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), any(), capture(contextSlot)) }

            assertThat(contextSlot.captured)
                .containsEntry("region", "EU")
                .containsEntry("city", "Berlin")
        }

        @Test
        fun `should resolve method call in context value`() {
            val event =
                PayloadApplicationEvent(
                    this,
                    MethodCallContextEvent(value = BigDecimal("123.45")),
                )

            eventMulticaster.multicastEvent(event)

            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), any(), capture(contextSlot)) }

            assertThat(contextSlot.captured).containsEntry("valueStr", "123.45")
        }

        @Test
        fun `should throw exception when context value expression returns null`() {
            val event = PayloadApplicationEvent(this, NullContextValueEvent(id = "evt-1", nullableField = null))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }

        @Test
        fun `should throw exception when context value is non-string`() {
            val event = PayloadApplicationEvent(this, NonStringContextEvent(id = "evt-1", count = 42))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }

        @Test
        fun `should throw exception when context SpEL expression is invalid`() {
            val event = PayloadApplicationEvent(this, InvalidContextSpELEvent(id = "evt-1"))

            assertThatThrownBy {
                eventMulticaster.multicastEvent(event)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Failed to resolve value from SpEL")
        }
    }

    @Nested
    @DisplayName("Combined Key and Context Resolution")
    inner class CombinedResolution {
        @Test
        fun `should resolve both key and context from same event`() {
            val order = Order(orderId = "order-789", customerId = "cust-456", total = BigDecimal("199.99"))
            val event = PayloadApplicationEvent(this, CompleteEvent(order = order, region = "US"))

            eventMulticaster.multicastEvent(event)

            val keySlot = slot<String>()
            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), capture(keySlot), capture(contextSlot)) }

            assertThat(keySlot.captured).isEqualTo("order-789")
            assertThat(contextSlot.captured)
                .containsEntry("customerId", "cust-456")
                .containsEntry("region", "US")
                .hasSize(2)
        }

        @Test
        fun `should handle complex SpEL expressions in both key and context`() {
            val address = Address(city = "Munich", region = "EU")
            val order = Order(orderId = "order-999", customerId = "cust-111", total = BigDecimal("299.50"))
            val event =
                PayloadApplicationEvent(
                    this,
                    ComplexSpELEvent(order = order, shippingAddress = address),
                )

            eventMulticaster.multicastEvent(event)

            val keySlot = slot<String>()
            val contextSlot = slot<Map<String, String>>()
            verify(exactly = 1) { outbox.schedule(any(), capture(keySlot), capture(contextSlot)) }

            assertThat(keySlot.captured).isEqualTo("order-999")
            assertThat(contextSlot.captured)
                .containsEntry("region", "EU")
                .containsEntry("total", "299.50")
        }
    }

    // Test event classes
    @OutboxEvent(key = "id")
    data class AnnotatedEvent(
        val id: String,
    )

    @OutboxEvent(key = "#this.customId")
    data class ThisReferenceKeyEvent(
        val customId: String,
    )

    @OutboxEvent(key = "#root.orderId")
    data class RootReferenceKeyEvent(
        val orderId: String,
    )

    @OutboxEvent(key = "address.region")
    data class NestedKeyEvent(
        val id: String,
        val address: Address,
    )

    @OutboxEvent(key = "")
    data class EmptyKeyEvent(
        val id: String,
    )

    @OutboxEvent(key = "#this.optionalKey")
    data class NullableKeyEvent(
        val id: String,
        val optionalKey: String?,
    )

    @OutboxEvent(key = "id")
    data class NonStringKeyEvent(
        val id: Int,
    )

    @OutboxEvent(key = "#invalid..expression")
    data class InvalidSpELKeyEvent(
        val id: String,
    )

    @OutboxEvent(key = "nonExistentField")
    data class NonExistentFieldKeyEvent(
        val id: String,
    )

    data class NotAnnotatedEvent(
        val id: String,
    )

    @OutboxEvent(
        key = "userId",
        context = [OutboxContextEntry(key = "email", value = "#this.email")],
    )
    data class SingleContextEvent(
        val userId: String,
        val email: String,
    )

    @OutboxEvent(
        key = "#this.orderId",
        context = [
            OutboxContextEntry(key = "customerId", value = "#this.customerId"),
            OutboxContextEntry(key = "total", value = "#this.total"),
        ],
    )
    data class MultipleContextEvent(
        val orderId: String,
        val customerId: String,
        val total: String,
    )

    @OutboxEvent(
        key = "id",
        context = [
            OutboxContextEntry(key = "eventType", value = "'STATIC_EVENT'"),
            OutboxContextEntry(key = "version", value = "'1.0'"),
        ],
    )
    data class StaticContextEvent(
        val id: String,
    )

    @OutboxEvent(
        key = "id",
        context = [
            OutboxContextEntry(key = "region", value = "#this.address.region"),
            OutboxContextEntry(key = "city", value = "#this.address.city"),
        ],
    )
    data class NestedContextEvent(
        val id: String,
        val address: Address,
    )

    @OutboxEvent(
        key = "id",
        context = [OutboxContextEntry(key = "valueStr", value = "#this.value.toString()")],
    )
    data class MethodCallContextEvent(
        val id: String = "id-1",
        val value: BigDecimal,
    )

    @OutboxEvent(
        key = "id",
        context = [OutboxContextEntry(key = "nullable", value = "#this.nullableField")],
    )
    data class NullContextValueEvent(
        val id: String,
        val nullableField: String?,
    )

    @OutboxEvent(
        key = "id",
        context = [OutboxContextEntry(key = "count", value = "#this.count")],
    )
    data class NonStringContextEvent(
        val id: String,
        val count: Int,
    )

    @OutboxEvent(
        key = "id",
        context = [OutboxContextEntry(key = "invalid", value = "#invalid..expression")],
    )
    data class InvalidContextSpELEvent(
        val id: String,
    )

    @OutboxEvent(
        key = "#this.order.orderId",
        context = [
            OutboxContextEntry(key = "customerId", value = "#this.order.customerId"),
            OutboxContextEntry(key = "region", value = "#this.region"),
        ],
    )
    data class CompleteEvent(
        val order: Order,
        val region: String,
    )

    @OutboxEvent(
        key = "#this.order.orderId",
        context = [
            OutboxContextEntry(key = "region", value = "#this.shippingAddress.region"),
            OutboxContextEntry(key = "total", value = "#this.order.total.toString()"),
        ],
    )
    data class ComplexSpELEvent(
        val order: Order,
        val shippingAddress: Address,
    )

    // Helper classes
    data class Order(
        val orderId: String,
        val customerId: String,
        val total: BigDecimal,
    )

    data class Address(
        val city: String,
        val region: String,
    )
}
