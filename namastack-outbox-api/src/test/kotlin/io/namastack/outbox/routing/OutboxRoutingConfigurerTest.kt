package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.Consumer

class OutboxRoutingConfigurerTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Test
    fun `route adds rule with selector`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        assertThat(configurer.rules()).hasSize(1)
    }

    @Test
    fun `route returns this for chaining`() {
        val configurer = OutboxRoutingConfigurer()
        val result =
            configurer.route(OutboxPayloadSelector.type(String::class.java)) {
                target("strings")
            }

        assertThat(result).isSameAs(configurer)
    }

    @Test
    fun `multiple routes are added in order`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }.route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                    target("ints")
                }

        assertThat(configurer.rules()).hasSize(2)
        assertThat(configurer.rules()[0].target("test", metadata)).isEqualTo("strings")
        assertThat(configurer.rules()[1].target(123, metadata)).isEqualTo("ints")
    }

    @Test
    fun `route with Consumer adds rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java), Consumer { it.target("strings") })

        assertThat(configurer.rules()).hasSize(1)
        assertThat(configurer.rules()[0].target("test", metadata)).isEqualTo("strings")
    }

    @Test
    fun `route with Consumer returns this for chaining`() {
        val configurer = OutboxRoutingConfigurer()
        val result =
            configurer.route(
                OutboxPayloadSelector.type(String::class.java),
                Consumer { it.target("strings") },
            )

        assertThat(result).isSameAs(configurer)
    }

    @Test
    fun `defaults sets default rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults {
                    target("default-target")
                }

        assertThat(configurer.defaultRule()).isNotNull
        assertThat(configurer.defaultRule()!!.target("any-payload", metadata)).isEqualTo("default-target")
    }

    @Test
    fun `defaults returns this for chaining`() {
        val configurer = OutboxRoutingConfigurer()
        val result =
            configurer.defaults {
                target("default")
            }

        assertThat(result).isSameAs(configurer)
    }

    @Test
    fun `defaults rule matches any payload`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults {
                    target("default")
                }

        val defaultRule = configurer.defaultRule()!!
        assertThat(defaultRule.matches("string", metadata)).isTrue()
        assertThat(defaultRule.matches(123, metadata)).isTrue()
        assertThat(defaultRule.matches(listOf(1, 2, 3), metadata)).isTrue()
    }

    @Test
    fun `defaults with Consumer sets default rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults(Consumer { it.target("default-target") })

        assertThat(configurer.defaultRule()).isNotNull
        assertThat(configurer.defaultRule()!!.target("any", metadata)).isEqualTo("default-target")
    }

    @Test
    fun `defaults with Consumer returns this for chaining`() {
        val configurer = OutboxRoutingConfigurer()
        val result = configurer.defaults(Consumer { it.target("default") })

        assertThat(result).isSameAs(configurer)
    }

    @Test
    fun `rules returns empty list when no routes configured`() {
        val configurer = OutboxRoutingConfigurer()

        assertThat(configurer.rules()).isEmpty()
    }

    @Test
    fun `rules returns immutable copy`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        val rules1 = configurer.rules()
        val rules2 = configurer.rules()

        assertThat(rules1).isNotSameAs(rules2)
        assertThat(rules1).isEqualTo(rules2)
    }

    @Test
    fun `defaultRule returns null when not configured`() {
        val configurer = OutboxRoutingConfigurer()

        assertThat(configurer.defaultRule()).isNull()
    }

    @Test
    fun `full configuration with routes and defaults`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                    target("orders")
                    key { payload, _ -> (payload as OrderEvent).orderId }
                    headers { _, meta -> meta.context }
                }.route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                    target("payments")
                    key { payload, _ -> (payload as PaymentEvent).paymentId }
                }.defaults {
                    target("domain-events")
                }

        assertThat(configurer.rules()).hasSize(2)
        assertThat(configurer.defaultRule()).isNotNull

        val orderEvent = OrderEvent("order-123")
        val paymentEvent = PaymentEvent("payment-456")

        // Test rule matching and target resolution
        assertThat(configurer.rules()[0].matches(orderEvent, metadata)).isTrue()
        assertThat(configurer.rules()[0].target(orderEvent, metadata)).isEqualTo("orders")
        assertThat(configurer.rules()[0].key(orderEvent, metadata)).isEqualTo("order-123")
        assertThat(configurer.rules()[0].headers(orderEvent, metadata)).containsEntry("tenant", "acme")

        assertThat(configurer.rules()[1].matches(paymentEvent, metadata)).isTrue()
        assertThat(configurer.rules()[1].target(paymentEvent, metadata)).isEqualTo("payments")
        assertThat(configurer.rules()[1].key(paymentEvent, metadata)).isEqualTo("payment-456")

        assertThat(configurer.defaultRule()!!.target("unknown", metadata)).isEqualTo("domain-events")
    }

    data class OrderEvent(
        val orderId: String,
    )

    data class PaymentEvent(
        val paymentId: String,
    )
}
