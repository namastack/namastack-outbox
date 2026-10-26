package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OutboxProcessingGroupTest {
    @ParameterizedTest
    @ValueSource(strings = ["default", "billing", "0", "orders.v2_eu-west"])
    fun `accepts valid group names unchanged`(group: String) {
        assertThat(OutboxProcessingGroup.validate(group)).isEqualTo(group)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", " ", "Billing", " billing", "billing ", "a:b", "bülling", ".billing", "-billing", "_billing"],
    )
    fun `rejects invalid group names without normalization`(group: String) {
        assertThatIllegalArgumentException().isThrownBy { OutboxProcessingGroup.validate(group) }
    }

    @Test
    fun `accepts at most 64 characters`() {
        assertThat(OutboxProcessingGroup.validate("a".repeat(64))).hasSize(64)
        assertThatIllegalArgumentException().isThrownBy { OutboxProcessingGroup.validate("a".repeat(65)) }
    }
}
