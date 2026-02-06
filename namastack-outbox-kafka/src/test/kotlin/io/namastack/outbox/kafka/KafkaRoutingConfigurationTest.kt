package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class KafkaRoutingConfigurationTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Test
    fun `routes by exact type match`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("order-created")
                    }
                }

        val payload = OrderCreatedEvent("order-123")
        assertThat(config.resolveTopic(payload, metadata)).isEqualTo("order-created")
    }

    @Test
    fun `routes by base type when specific type not matched`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("order-created")
                    }
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                    }
                }

        val createdPayload = OrderCreatedEvent("order-123")
        val canceledPayload = OrderCanceledEvent("order-456")

        assertThat(config.resolveTopic(createdPayload, metadata)).isEqualTo("order-created")
        assertThat(config.resolveTopic(canceledPayload, metadata)).isEqualTo("orders")
    }

    @Test
    fun `first match wins - order matters`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                    }
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("order-created")
                    }
                }

        // Even though OrderCreatedEvent is more specific, OrderEvent is first
        val payload = OrderCreatedEvent("order-123")
        assertThat(config.resolveTopic(payload, metadata)).isEqualTo("orders")
    }

    @Test
    fun `falls back to default route when no match`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                    }
                    defaults {
                        topic("domain-events")
                    }
                }

        val payload = PaymentEvent("payment-123")
        assertThat(config.resolveTopic(payload, metadata)).isEqualTo("domain-events")
    }

    @Test
    fun `throws when no matching route and no default`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                    }
                }

        val payload = PaymentEvent("payment-123")
        assertThatThrownBy { config.resolveTopic(payload, metadata) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No routing rule found")
    }

    @Test
    fun `extracts key using custom extractor`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("orders")
                        key { payload, _ -> (payload as OrderCreatedEvent).orderId }
                    }
                }

        val payload = OrderCreatedEvent("my-order-id")
        assertThat(config.extractKey(payload, metadata)).isEqualTo("my-order-id")
    }

    @Test
    fun `key defaults to metadata key`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("orders")
                    }
                }

        val payload = OrderCreatedEvent("order-123")
        assertThat(config.extractKey(payload, metadata)).isEqualTo("order-123")
    }

    @Test
    fun `builds headers using custom provider`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("orders")
                        headers { _, meta -> meta.context + ("eventType" to "OrderCreated") }
                    }
                }

        val payload = OrderCreatedEvent("order-123")
        val headers = config.buildHeaders(payload, metadata)

        assertThat(headers)
            .containsEntry("tenant", "acme")
            .containsEntry("eventType", "OrderCreated")
    }

    @Test
    fun `routes by predicate`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, meta -> meta.context["tenant"] == "acme" }) {
                        topic("acme-events")
                    }
                    defaults {
                        topic("other-events")
                    }
                }

        assertThat(config.resolveTopic("any-payload", metadata)).isEqualTo("acme-events")

        val otherMetadata = metadata.copy(context = mapOf("tenant" to "other"))
        assertThat(config.resolveTopic("any-payload", otherMetadata)).isEqualTo("other-events")
    }

    @Test
    fun `routes by context value`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.contextValue("tenant", "acme")) {
                        topic("acme-events")
                    }
                    defaults {
                        topic("default-events")
                    }
                }

        assertThat(config.resolveTopic("any-payload", metadata)).isEqualTo("acme-events")
    }

    @Test
    fun `resolves dynamic topic`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic { payload, _ -> "orders-${(payload as OrderEvent).type}" }
                    }
                }

        val payload = OrderCreatedEvent("order-123")
        assertThat(config.resolveTopic(payload, metadata)).isEqualTo("orders-created")
    }

    @Test
    fun `routing can be called with Java Consumer`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing { routing ->
                    routing.route(OutboxPayloadSelector.type(String::class.java)) { rule ->
                        rule.topic("strings")
                    }
                }

        assertThat(config.resolveTopic("test", metadata)).isEqualTo("strings")
    }

    // Test fixtures

    interface DomainEvent

    abstract class OrderEvent(
        val orderId: String,
        val type: String,
    ) : DomainEvent

    class OrderCreatedEvent(
        orderId: String,
    ) : OrderEvent(orderId, "created")

    class OrderCanceledEvent(
        orderId: String,
    ) : OrderEvent(orderId, "canceled")

    class PaymentEvent(
        val paymentId: String,
    ) : DomainEvent
}
