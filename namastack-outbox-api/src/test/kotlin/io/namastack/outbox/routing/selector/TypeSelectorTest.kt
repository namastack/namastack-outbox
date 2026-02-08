package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TypeSelectorTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    @Test
    fun `matches returns true for exact type match`() {
        val selector = OutboxPayloadSelector.type(OrderCreatedEvent::class.java)
        val payload = OrderCreatedEvent("order-123")

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns true for subclass`() {
        val selector = OutboxPayloadSelector.type(OrderEvent::class.java)
        val payload = OrderCreatedEvent("order-123")

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns true for interface implementation`() {
        val selector = OutboxPayloadSelector.type(DomainEvent::class.java)
        val payload = OrderCreatedEvent("order-123")

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns false for unrelated type`() {
        val selector = OutboxPayloadSelector.type(PaymentEvent::class.java)
        val payload = OrderCreatedEvent("order-123")

        assertThat(selector.matches(payload, metadata)).isFalse()
    }

    @Test
    fun `matches returns false for parent type payload against child selector`() {
        val selector = OutboxPayloadSelector.type(OrderCreatedEvent::class.java)
        val payload = object : OrderEvent("order-456") {}

        assertThat(selector.matches(payload, metadata)).isFalse()
    }

    interface DomainEvent

    abstract class OrderEvent(
        val orderId: String,
    ) : DomainEvent

    class OrderCreatedEvent(
        orderId: String,
    ) : OrderEvent(orderId)

    abstract class PaymentEvent(
        val paymentId: String,
    ) : DomainEvent
}
