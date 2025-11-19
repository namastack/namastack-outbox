package io.namastack.outbox.routing

import io.namastack.outbox.OutboxRecordTestFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RoutingConfiguration Tests")
class RoutingConfigurationTest {
    private val testRecord = OutboxRecordTestFactory.outboxRecord()

    @Nested
    @DisplayName("RoutingConfiguration.Builder Creation")
    inner class BuilderCreation {
        @Test
        fun `should create builder with factory method`() {
            val builder = RoutingConfiguration.builder()

            assertThat(builder).isNotNull
        }

        @Test
        fun `should have static builder method`() {
            val builder = RoutingConfiguration.builder()

            assertThat(builder).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration.Builder.route()")
    inner class RouteFunction {
        @Test
        fun `should add specific route for event type`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .build()

            val rule = config.getRoute("order.created")
            assertThat(rule.target(testRecord).target).isEqualTo("orders")
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingConfiguration.builder()
            val result = builder.route("event.type") { target("target") }

            assertThat(result).isNotNull
        }

        @Test
        fun `should override route when called with same event type`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders-v1") }
                    .route("order.created") { target("orders-v2") }
                    .build()

            val rule = config.getRoute("order.created")
            assertThat(rule.target(testRecord).target).isEqualTo("orders-v2")
        }

        @Test
        fun `should support complex routing rules`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") {
                        target("orders", "orderId")
                        mapper { it.payload }
                        header("version", "1.0")
                    }.build()

            val rule = config.getRoute("order.created")
            assertThat(rule.target(testRecord).target).isEqualTo("orders")
            assertThat(rule.target(testRecord).key).isEqualTo("orderId")
            assertThat(rule.headers(testRecord)).isEqualTo(mapOf("version" to "1.0"))
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration.Builder.routeAll()")
    inner class RouteAllFunction {
        @Test
        fun `should add wildcard route`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .routeAll { target("fallback") }
                    .build()

            val rule = config.getRoute("any.event.type")
            assertThat(rule.target(testRecord).target).isEqualTo("fallback")
        }

        @Test
        fun `should override wildcard when called multiple times`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .routeAll { target("fallback-v1") }
                    .routeAll { target("fallback-v2") }
                    .build()

            val rule = config.getRoute("unknown")
            assertThat(rule.target(testRecord).target).isEqualTo("fallback-v2")
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingConfiguration.builder()
            val result = builder.routeAll { target("fallback") }

            assertThat(result).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration.getRoute()")
    inner class GetRoute {
        @Test
        fun `should return specific route for matching event type`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .routeAll { target("fallback") }
                    .build()

            val rule = config.getRoute("order.created")
            assertThat(rule.target(testRecord).target).isEqualTo("orders")
        }

        @Test
        fun `should return wildcard route when no specific route exists`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .routeAll { target("fallback") }
                    .build()

            val rule = config.getRoute("unknown.event")
            assertThat(rule.target(testRecord).target).isEqualTo("fallback")
        }

        @Test
        fun `should prioritize specific route over wildcard`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .routeAll { target("fallback") }
                    .route("order.created") { target("orders") }
                    .build()

            val rule = config.getRoute("order.created")
            assertThat(rule.target(testRecord).target).isEqualTo("orders")
        }

        @Test
        fun `should throw IllegalArgumentException when no route found`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .build()

            assertThatThrownBy { config.getRoute("unknown.event") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("No routing rule found")
                .hasMessageContaining("unknown.event")
        }

        @Test
        fun `should provide helpful error message with suggestions`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .build()

            assertThatThrownBy { config.getRoute("payment.received") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(".route(")
                .hasMessageContaining("routeAll()")
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration.Builder.build()")
    inner class BuildFunction {
        @Test
        fun `should build immutable RoutingConfiguration`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .build()

            assertThat(config).isNotNull
        }

        @Test
        fun `should support empty configuration`() {
            val config = RoutingConfiguration.builder().build()

            assertThat(config).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration Integration")
    inner class Integration {
        @Test
        fun `should handle multiple specific routes and wildcard`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") { target("orders") }
                    .route("order.shipped") { target("orders-shipped") }
                    .route("payment.received") { target("payments") }
                    .routeAll { target("fallback") }
                    .build()

            assertThat(config.getRoute("order.created").target(testRecord).target).isEqualTo("orders")
            assertThat(config.getRoute("order.shipped").target(testRecord).target).isEqualTo("orders-shipped")
            assertThat(config.getRoute("payment.received").target(testRecord).target).isEqualTo("payments")
            assertThat(config.getRoute("unknown").target(testRecord).target).isEqualTo("fallback")
        }

        @Test
        fun `should support complete fluent configuration`() {
            val config =
                RoutingConfiguration
                    .builder()
                    .route("order.created") {
                        target("kafka:orders", "orderId")
                        mapper { it.payload }
                        header("version", "1.0")
                        header("source", "order-service")
                    }.route("order.shipped") {
                        target("kafka:orders-shipped")
                        header("version", "1.0")
                    }.routeAll {
                        target("kafka:fallback")
                    }.build()

            val createdRule = config.getRoute("order.created")
            assertThat(createdRule.target(testRecord).target).isEqualTo("kafka:orders")
            assertThat(createdRule.target(testRecord).key).isEqualTo("orderId")
            assertThat(createdRule.headers(testRecord)).isEqualTo(
                mapOf("version" to "1.0", "source" to "order-service"),
            )

            val shippedRule = config.getRoute("order.shipped")
            assertThat(shippedRule.target(testRecord).target).isEqualTo("kafka:orders-shipped")

            val unknownRule = config.getRoute("unknown")
            assertThat(unknownRule.target(testRecord).target).isEqualTo("kafka:fallback")
        }
    }

    @Nested
    @DisplayName("RoutingConfiguration Companion Object")
    inner class CompanionObject {
        @Test
        fun `should have WILDCARD_ROUTE_KEY constant`() {
            assertThat(RoutingConfiguration.WILDCARD_ROUTE_KEY).isEqualTo("*")
        }

        @Test
        fun `should have static builder() method`() {
            val builder = RoutingConfiguration.builder()

            assertThat(builder).isNotNull
        }

        @Test
        fun `wildcard route key is star`() {
            assertThat(RoutingConfiguration.WILDCARD_ROUTE_KEY).isEqualTo("*")
        }

        @Test
        fun `builder creates new instances`() {
            val builder1 = RoutingConfiguration.builder()
            val builder2 = RoutingConfiguration.builder()

            assertThat(builder1).isNotNull
            assertThat(builder2).isNotNull
            assertThat(builder1).isNotSameAs(builder2)
        }
    }
}
