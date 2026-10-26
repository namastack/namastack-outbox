package io.namastack.outbox.consumer

import io.namastack.outbox.handler.OutboxConsumerGroup

/**
 * Encodes a consumer group and business key in the existing record key column.
 *
 * Non-default groups use `~ns.cg1~<group>:<business key>`. Group names cannot contain a colon,
 * so the first colon after the prefix separates the group from the verbatim business key.
 * Default-group keys are unencoded.
 *
 * Only canonical encodings are decoded. All other stored keys belong to the default group.
 * A legacy key that exactly matches a canonical encoding is indistinguishable from a grouped key;
 * this is an accepted limitation of using the existing column without a schema migration.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal object OutboxRecordKey {
    private const val PREFIX = "~ns.cg1~"
    private const val MAX_ENCODED_BYTES = 255

    /**
     * Encodes a key for storage, retaining unencoded keys for the default group.
     *
     * Newly supplied business keys must not start with the reserved prefix, even in the default
     * group. Grouped keys have a conservative 255-byte UTF-8 limit for the existing SQL schemas.
     * Default-group keys retain their existing length behavior.
     *
     * @throws IllegalArgumentException if the group is invalid, the prefix is reserved, or the
     * encoded key exceeds the byte limit
     */
    fun encode(
        consumerGroup: String,
        businessKey: String,
    ): String {
        OutboxConsumerGroup.validate(consumerGroup)

        require(!businessKey.startsWith(PREFIX)) { "Business key uses the reserved consumer-group prefix $PREFIX" }

        if (consumerGroup == OutboxConsumerGroup.DEFAULT) return businessKey

        val encoded = "$PREFIX$consumerGroup:$businessKey"
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Encoded consumer-group key exceeds the $MAX_ENCODED_BYTES-byte UTF-8 record_key limit"
        }

        return encoded
    }

    /**
     * Reads both values from a stored key. Malformed encodings and unknown versions remain
     * unchanged business keys in the default group, so legacy records can still be read.
     */
    fun decode(recordKey: String): DecodedKey {
        val legacy = DecodedKey(OutboxConsumerGroup.DEFAULT, recordKey)
        if (!recordKey.startsWith(PREFIX)) return legacy

        return try {
            decodeGrouped(recordKey)
        } catch (_: IllegalArgumentException) {
            legacy
        }
    }

    private fun decodeGrouped(recordKey: String): DecodedKey {
        val separator = recordKey.indexOf(':', PREFIX.length)
        require(separator > PREFIX.length) { "Missing consumer-group separator or name" }

        val group = recordKey.substring(PREFIX.length, separator)
        val key = recordKey.substring(separator + 1)
        require(group != OutboxConsumerGroup.DEFAULT && encode(group, key) == recordKey) {
            "Non-canonical consumer-group record key"
        }

        return DecodedKey(group, key)
    }

    /** Group and original business key to expose to handlers and messaging integrations. */
    data class DecodedKey(
        val consumerGroup: String,
        val businessKey: String,
    )
}
