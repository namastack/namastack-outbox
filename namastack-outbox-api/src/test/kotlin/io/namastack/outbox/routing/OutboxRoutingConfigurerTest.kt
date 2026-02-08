package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        assertThat(configurer.resolveTarget("test", metadata)).isEqualTo("strings")
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
        assertThat(configurer.resolveTarget("test", metadata)).isEqualTo("strings")
        assertThat(configurer.resolveTarget(123, metadata)).isEqualTo("ints")
    }

    @Test
    fun `route with Consumer adds rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java), Consumer { it.target("strings") })

        assertThat(configurer.rules()).hasSize(1)
        assertThat(configurer.resolveTarget("test", metadata)).isEqualTo("strings")
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
        assertThat(configurer.resolveTarget("any-payload", metadata)).isEqualTo("default-target")
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

        assertThat(configurer.resolveTarget("string", metadata)).isEqualTo("default")
        assertThat(configurer.resolveTarget(123, metadata)).isEqualTo("default")
        assertThat(configurer.resolveTarget(listOf(1, 2, 3), metadata)).isEqualTo("default")
    }

    @Test
    fun `defaults with Consumer sets default rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults(Consumer { it.target("default-target") })

        assertThat(configurer.defaultRule()).isNotNull
        assertThat(configurer.resolveTarget("any", metadata)).isEqualTo("default-target")
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
    fun `findRule returns first matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }.route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                    target("ints")
                }

        val rule = configurer.findRule("test", metadata)

        assertThat(rule).isNotNull
        assertThat(rule!!.target("test", metadata)).isEqualTo("strings")
    }

    @Test
    fun `findRule returns default when no match`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }.defaults {
                    target("default")
                }

        val rule = configurer.findRule(123, metadata)

        assertThat(rule).isNotNull
        assertThat(rule!!.target(123, metadata)).isEqualTo("default")
    }

    @Test
    fun `findRule returns null when no match and no default`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        val rule = configurer.findRule(123, metadata)

        assertThat(rule).isNull()
    }

    @Test
    fun `findRule evaluates rules in order - first match wins`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                    target("first")
                }.route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                    target("second")
                }

        val rule = configurer.findRule("any", metadata)

        assertThat(rule!!.target("any", metadata)).isEqualTo("first")
    }

    @Test
    fun `resolveTarget returns target from matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        assertThat(configurer.resolveTarget("test", metadata)).isEqualTo("strings")
    }

    @Test
    fun `resolveTarget returns target from default rule when no match`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults {
                    target("default")
                }

        assertThat(configurer.resolveTarget(123, metadata)).isEqualTo("default")
    }

    @Test
    fun `resolveTarget throws when no matching rule and no default`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        assertThatThrownBy { configurer.resolveTarget(123, metadata) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No routing rule found")
            .hasMessageContaining("java.lang.Integer")
    }

    @Test
    fun `extractKey returns key from matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                    key { payload, _ -> "key-$payload" }
                }

        assertThat(configurer.extractKey("test", metadata)).isEqualTo("key-test")
    }

    @Test
    fun `extractKey returns key from default rule when no match`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults {
                    target("default")
                    key { _, meta -> meta.key }
                }

        assertThat(configurer.extractKey(123, metadata)).isEqualTo("test-key")
    }

    @Test
    fun `extractKey returns null when no matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        assertThat(configurer.extractKey(123, metadata)).isNull()
    }

    @Test
    fun `buildHeaders returns headers from matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                    headers { _, meta -> meta.context }
                }

        assertThat(configurer.buildHeaders("test", metadata))
            .containsEntry("tenant", "acme")
    }

    @Test
    fun `buildHeaders returns headers from default rule when no match`() {
        val configurer =
            OutboxRoutingConfigurer()
                .defaults {
                    target("default")
                    headers { _, _ -> mapOf("default" to "true") }
                }

        assertThat(configurer.buildHeaders(123, metadata))
            .containsEntry("default", "true")
    }

    @Test
    fun `buildHeaders returns empty map when no matching rule`() {
        val configurer =
            OutboxRoutingConfigurer()
                .route(OutboxPayloadSelector.type(String::class.java)) {
                    target("strings")
                }

        assertThat(configurer.buildHeaders(123, metadata)).isEmpty()
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

        val orderEvent = OrderEvent("order-123")
        val paymentEvent = PaymentEvent("payment-456")
        val unknownEvent = "unknown"

        assertThat(configurer.resolveTarget(orderEvent, metadata)).isEqualTo("orders")
        assertThat(configurer.extractKey(orderEvent, metadata)).isEqualTo("order-123")
        assertThat(configurer.buildHeaders(orderEvent, metadata)).containsEntry("tenant", "acme")

        assertThat(configurer.resolveTarget(paymentEvent, metadata)).isEqualTo("payments")
        assertThat(configurer.extractKey(paymentEvent, metadata)).isEqualTo("payment-456")

        assertThat(configurer.resolveTarget(unknownEvent, metadata)).isEqualTo("domain-events")
    }

    data class OrderEvent(
        val orderId: String,
    )

    data class PaymentEvent(
        val paymentId: String,
    )
}
