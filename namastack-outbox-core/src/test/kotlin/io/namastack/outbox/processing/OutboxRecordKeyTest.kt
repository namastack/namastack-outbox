package io.namastack.outbox.processing

import io.namastack.outbox.handler.OutboxProcessingGroup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class OutboxRecordKeyTest {
    @Test
    fun `uses the versioned format for a non-default group`() {
        assertThat(OutboxRecordKey.encode("billing", "order-42"))
            .isEqualTo("~ns.pg1~billing:order-42")

        assertDecoded("~ns.pg1~billing:order-42", "billing", "order-42")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "order-42", "a:b::c", "订单-😀-ä", " leading and trailing ", "key/~ns.pg1~billing:x"])
    fun `retains default-group keys exactly`(key: String) {
        assertThat(OutboxRecordKey.encode(OutboxProcessingGroup.DEFAULT, key)).isEqualTo(key)
        assertDecoded(key, OutboxProcessingGroup.DEFAULT, key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "order-42", "a:b::c", "订单-😀-ä", " leading and trailing ", "key/~ns.pg1~billing:x"])
    fun `round trips business keys without escaping or normalization`(key: String) {
        assertDecoded(OutboxRecordKey.encode("billing", key), "billing", key)
    }

    @Test
    fun `separates groups sharing a business key and keys sharing a group`() {
        val keys =
            listOf(
                OutboxRecordKey.encode("billing", "order-42"),
                OutboxRecordKey.encode("notifications", "order-42"),
                OutboxRecordKey.encode(OutboxProcessingGroup.DEFAULT, "order-42"),
                OutboxRecordKey.encode("billing", "order-43"),
            )

        assertThat(keys).doesNotHaveDuplicates()
    }

    @Test
    fun `round trips the maximum group name length`() {
        val group = "a".repeat(64)

        assertDecoded(OutboxRecordKey.encode(group, "order-42"), group, "order-42")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Billing", "a:b"])
    fun `rejects invalid groups when encoding`(group: String) {
        assertThatIllegalArgumentException()
            .isThrownBy { OutboxRecordKey.encode(group, "order-42") }
            .withMessageContaining("Processing group")
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "billing"])
    fun `rejects reserved business-key prefixes for all groups`(group: String) {
        listOf("~ns.pg1~", "~ns.pg1~malformed", "~ns.pg1~billing:order-42").forEach { key ->
            assertThatIllegalArgumentException()
                .isThrownBy { OutboxRecordKey.encode(group, key) }
                .withMessageContaining("reserved")
        }
    }

    @Test
    fun `allows exactly 255 encoded ASCII bytes and rejects an extra byte`() {
        // The billing header occupies 16 bytes, leaving 239 bytes for the original key.
        val key = "a".repeat(239)
        val encoded = OutboxRecordKey.encode("billing", key)

        assertThat(encoded.toByteArray(Charsets.UTF_8)).hasSize(255)
        assertDecoded(encoded, "billing", key)
        assertThatIllegalArgumentException()
            .isThrownBy { OutboxRecordKey.encode("billing", key + "a") }
            .withMessageContaining("255-byte")
    }

    @ParameterizedTest
    @CsvSource("é,119,x", "😀,59,xxx")
    fun `counts Unicode keys in UTF-8 bytes`(
        character: String,
        count: Int,
        suffix: String,
    ) {
        val key = character.repeat(count) + suffix
        val encoded = OutboxRecordKey.encode("billing", key)

        assertThat(encoded.toByteArray(Charsets.UTF_8)).hasSize(255)
        assertDecoded(encoded, "billing", key)
        assertThatIllegalArgumentException()
            .isThrownBy { OutboxRecordKey.encode("billing", key + "x") }
            .withMessageContaining("255-byte")
    }

    @Test
    fun `does not impose a new length limit on default-group keys`() {
        val key = "ä".repeat(300)

        assertThat(OutboxRecordKey.encode(OutboxProcessingGroup.DEFAULT, key)).isEqualTo(key)
        assertDecoded(key, OutboxProcessingGroup.DEFAULT, key)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "~ns.pg1~",
            "~ns.pg1~:key",
            "~ns.pg1~billing",
            "~ns.pg1~billing!key",
            "~ns.pg1~Billing:key",
            "~ns.pg1~bülling:key",
            "~ns.pg1~ billing:key",
            "~ns.pg1~billing :key",
            "~ns.pg1~-billing:key",
            "~ns.pg1~default:key",
            "~ns.pg1~billing:~ns.pg1~billing:key",
            "~ns.pg2~billing:key",
        ],
    )
    fun `preserves malformed non-canonical and unknown-version legacy keys`(key: String) {
        assertDecoded(key, OutboxProcessingGroup.DEFAULT, key)
    }

    @Test
    fun `preserves encoded-looking legacy keys with an invalid group length`() {
        val key = "~ns.pg1~" + "a".repeat(65) + ":order-42"

        assertDecoded(key, OutboxProcessingGroup.DEFAULT, key)
    }

    @Test
    fun `preserves oversized encoded-looking legacy keys`() {
        val key = "~ns.pg1~billing:" + "a".repeat(240)

        assertDecoded(key, OutboxProcessingGroup.DEFAULT, key)
    }

    @Test
    fun `interprets a legacy key matching the encoded format as a grouped key`() {
        // Accepted ambiguity: the persisted string alone cannot identify its origin.
        assertDecoded("~ns.pg1~billing:legacy-order", "billing", "legacy-order")
    }

    private fun assertDecoded(
        storedKey: String,
        group: String,
        businessKey: String,
    ) {
        val decoded = OutboxRecordKey.decode(storedKey)

        assertThat(decoded.processingGroup).isEqualTo(group)
        assertThat(decoded.businessKey).isEqualTo(businessKey)
    }
}
