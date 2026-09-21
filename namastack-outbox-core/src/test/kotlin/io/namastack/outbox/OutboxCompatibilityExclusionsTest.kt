package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxCompatibilityExclusionsTest {
    @Test
    fun `tracks unavailable payload types and handlers`() {
        val exclusions = OutboxCompatibilityExclusions()

        assertThat(exclusions.addUnavailablePayloadType("example.MissingPayload")).isTrue()
        assertThat(exclusions.addUnavailableHandlerId("missing-handler")).isTrue()
        assertThat(exclusions.addUnavailablePayloadType("example.MissingPayload")).isFalse()

        assertThat(exclusions.unavailablePayloadTypes).containsExactly("example.MissingPayload")
        assertThat(exclusions.unavailableHandlerIds).containsExactly("missing-handler")
        assertThat(exclusions.isEmpty).isFalse()
    }

    @Test
    fun `keeps a bounded number of identifiers per kind`() {
        val exclusions = OutboxCompatibilityExclusions()

        repeat(OutboxCompatibilityExclusions.MAXIMUM_IDENTIFIERS_PER_KIND + 1) {
            exclusions.addUnavailablePayloadType("payload-$it")
            exclusions.addUnavailableHandlerId("handler-$it")
        }

        assertThat(exclusions.unavailablePayloadTypes)
            .hasSize(OutboxCompatibilityExclusions.MAXIMUM_IDENTIFIERS_PER_KIND)
            .doesNotContain("payload-0")
            .contains("payload-${OutboxCompatibilityExclusions.MAXIMUM_IDENTIFIERS_PER_KIND}")
        assertThat(exclusions.unavailableHandlerIds)
            .hasSize(OutboxCompatibilityExclusions.MAXIMUM_IDENTIFIERS_PER_KIND)
            .doesNotContain("handler-0")
            .contains("handler-${OutboxCompatibilityExclusions.MAXIMUM_IDENTIFIERS_PER_KIND}")
    }
}
