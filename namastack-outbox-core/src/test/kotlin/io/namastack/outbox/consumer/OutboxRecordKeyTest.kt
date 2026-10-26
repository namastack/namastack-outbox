package io.namastack.outbox.consumer

import io.namastack.outbox.handler.OutboxConsumerGroup
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
            .isEqualTo("~ns.cg1~billing:order-42")

        assertDecoded("~ns.cg1~billing:order-42", "billing", "order-42")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "order-42", "a:b::c", "订单-😀-ä", " leading and trailing ", "key/~ns.cg1~billing:x"])
    fun `retains default-group keys exactly`(key: String) {
        assertThat(OutboxRecordKey.encode(OutboxConsumerGroup.DEFAULT, key)).isEqualTo(key)
        assertDecoded(key, OutboxConsumerGroup.DEFAULT, key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "order-42", "a:b::c", "订单-😀-ä", " leading and trailing ", "key/~ns.cg1~billing:x"])
    fun `round trips business keys without escaping or normalization`(key: String) {
        assertDecoded(OutboxRecordKey.encode("billing", key), "billing", key)
    }

    @Test
    fun `separates groups sharing a business key and keys sharing a group`() {
        val keys =
            listOf(
                OutboxRecordKey.encode("billing", "order-42"),
                OutboxRecordKey.encode("notifications", "order-42"),
                OutboxRecordKey.encode(OutboxConsumerGroup.DEFAULT, "order-42"),
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
            .withMessageContaining("Consumer group")
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "billing"])
    fun `rejects reserved business-key prefixes for all groups`(group: String) {
        listOf("~ns.cg1~", "~ns.cg1~malformed", "~ns.cg1~billing:order-42").forEach { key ->
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

        assertThat(OutboxRecordKey.encode(OutboxConsumerGroup.DEFAULT, key)).isEqualTo(key)
        assertDecoded(key, OutboxConsumerGroup.DEFAULT, key)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "~ns.cg1~",
            "~ns.cg1~:key",
            "~ns.cg1~billing",
            "~ns.cg1~billing!key",
            "~ns.cg1~Billing:key",
            "~ns.cg1~bülling:key",
            "~ns.cg1~ billing:key",
            "~ns.cg1~billing :key",
            "~ns.cg1~-billing:key",
            "~ns.cg1~default:key",
            "~ns.cg1~billing:~ns.cg1~billing:key",
            "~ns.cg2~billing:key",
        ],
    )
    fun `preserves malformed non-canonical and unknown-version legacy keys`(key: String) {
        assertDecoded(key, OutboxConsumerGroup.DEFAULT, key)
    }

    @Test
    fun `preserves encoded-looking legacy keys with an invalid group length`() {
        val key = "~ns.cg1~" + "a".repeat(65) + ":order-42"

        assertDecoded(key, OutboxConsumerGroup.DEFAULT, key)
    }

    @Test
    fun `preserves oversized encoded-looking legacy keys`() {
        val key = "~ns.cg1~billing:" + "a".repeat(240)

        assertDecoded(key, OutboxConsumerGroup.DEFAULT, key)
    }

    @Test
    fun `interprets a legacy key matching the encoded format as a grouped key`() {
        // Accepted ambiguity: the persisted string alone cannot identify its origin.
        assertDecoded("~ns.cg1~billing:legacy-order", "billing", "legacy-order")
    }

    private fun assertDecoded(
        storedKey: String,
        group: String,
        businessKey: String,
    ) {
        val decoded = OutboxRecordKey.decode(storedKey)

        assertThat(decoded.consumerGroup).isEqualTo(group)
        assertThat(decoded.businessKey).isEqualTo(businessKey)
    }
}
