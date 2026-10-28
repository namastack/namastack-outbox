package io.namastack.outbox.processing

import io.namastack.outbox.handler.OutboxProcessingGroup

/**
 * Encodes a processing group and business key in the existing record key column.
 *
 * Non-default groups use `~ns.pg1~<group>:<business key>`. Group names cannot contain a colon,
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
    private const val PREFIX = "~ns.pg1~"
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
        processingGroup: String,
        businessKey: String,
    ): String {
        OutboxProcessingGroup.validate(processingGroup)

        require(!businessKey.startsWith(PREFIX)) { "Business key uses the reserved processing-group prefix $PREFIX" }

        if (processingGroup == OutboxProcessingGroup.DEFAULT) return businessKey

        val encoded = "$PREFIX$processingGroup:$businessKey"
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Encoded processing-group key exceeds the $MAX_ENCODED_BYTES-byte UTF-8 record_key limit"
        }

        return encoded
    }

    /**
     * Reads both values from a stored key. Malformed encodings and unknown versions remain
     * unchanged business keys in the default group, so legacy records can still be read.
     */
    fun decode(recordKey: String): DecodedKey {
        val legacy = DecodedKey(OutboxProcessingGroup.DEFAULT, recordKey)
        if (!recordKey.startsWith(PREFIX)) return legacy

        return try {
            decodeGrouped(recordKey)
        } catch (_: IllegalArgumentException) {
            legacy
        }
    }

    private fun decodeGrouped(recordKey: String): DecodedKey {
        val separator = recordKey.indexOf(':', PREFIX.length)
        require(separator > PREFIX.length) { "Missing processing-group separator or name" }

        val group = recordKey.substring(PREFIX.length, separator)
        val key = recordKey.substring(separator + 1)

        OutboxProcessingGroup.validate(group)

        require(group != OutboxProcessingGroup.DEFAULT && !key.startsWith(PREFIX)) {
            "Non-canonical processing-group record key"
        }
        require(recordKey.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Processing-group record key exceeds the storage limit"
        }

        return DecodedKey(group, key)
    }

    /** Group and original business key to expose to handlers and messaging integrations. */
    data class DecodedKey(
        val processingGroup: String,
        val businessKey: String,
    )
}
