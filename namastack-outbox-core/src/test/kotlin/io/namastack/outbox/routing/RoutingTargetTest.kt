package io.namastack.outbox.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RoutingTarget Tests")
class RoutingTargetTest {
    @Nested
    @DisplayName("RoutingTarget Creation")
    inner class Creation {
        @Test
        fun `should create RoutingTarget with target only`() {
            val target = RoutingTarget("kafka:orders")

            assertThat(target.target).isEqualTo("kafka:orders")
            assertThat(target.key).isNull()
        }

        @Test
        fun `should create RoutingTarget with target and key`() {
            val target = RoutingTarget("kafka:orders", "orderId")

            assertThat(target.target).isEqualTo("kafka:orders")
            assertThat(target.key).isEqualTo("orderId")
        }

        @Test
        fun `should create RoutingTarget with all fields`() {
            val target = RoutingTarget("kafka:orders", "orderId")

            assertThat(target.target).isEqualTo("kafka:orders")
            assertThat(target.key).isEqualTo("orderId")
        }
    }

    @Nested
    @DisplayName("RoutingTarget.withKey()")
    inner class WithKey {
        @Test
        fun `should update key`() {
            val target = RoutingTarget("kafka:orders")
            val updated = target.withKey("orderId")

            assertThat(updated.target).isEqualTo("kafka:orders")
            assertThat(updated.key).isEqualTo("orderId")
        }

        @Test
        fun `should override existing key`() {
            val target = RoutingTarget("kafka:orders", "oldKey")
            val updated = target.withKey("newKey")

            assertThat(updated.key).isEqualTo("newKey")
        }

        @Test
        fun `should clear key when passed null`() {
            val target = RoutingTarget("kafka:orders", "orderId")
            val updated = target.withKey(null)

            assertThat(updated.key).isNull()
        }

        @Test
        fun `should return new instance`() {
            val target = RoutingTarget("kafka:orders")
            val updated = target.withKey("key")

            assertThat(updated.target).isEqualTo(target.target)
            assertThat(target.key).isNull()
            assertThat(updated.key).isEqualTo("key")
        }
    }

    @Nested
    @DisplayName("RoutingTarget.forTarget()")
    inner class ForTarget {
        @Test
        fun `should create RoutingTarget with target only`() {
            val target = RoutingTarget.forTarget("kafka:orders")

            assertThat(target.target).isEqualTo("kafka:orders")
            assertThat(target.key).isNull()
        }
    }

    @Nested
    @DisplayName("RoutingTarget Data Class Properties")
    inner class DataClassProperties {
        @Test
        fun `should be equal when all fields match`() {
            val target1 = RoutingTarget("kafka:orders", "orderId")
            val target2 = RoutingTarget("kafka:orders", "orderId")

            assertThat(target1).isEqualTo(target2)
        }

        @Test
        fun `should have different hash when fields differ`() {
            val target1 = RoutingTarget("kafka:orders", "orderId")
            val target2 = RoutingTarget("kafka:orders", "customerId")

            assertThat(target1.hashCode()).isNotEqualTo(target2.hashCode())
        }

        @Test
        fun `should support copy() function`() {
            val target = RoutingTarget("kafka:orders", "orderId")
            val copied = target.copy(key = "newKey")

            assertThat(copied.target).isEqualTo("kafka:orders")
            assertThat(copied.key).isEqualTo("newKey")
        }
    }
}
