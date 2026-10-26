package io.namastack.outbox.handler

/**
 * Default name and validation shared by consumer group declarations.
 *
 * Group names use lowercase ASCII to avoid case-only differences on databases with
 * case-insensitive collations. Names contain 1–64 letters, digits, dots, underscores or
 * hyphens and start with a letter or digit.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
object OutboxConsumerGroup {
    /** Group used by handlers without an explicit consumer group declaration. */
    const val DEFAULT: String = "default"

    private val namePattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /**
     * Validates a group name without trimming or normalizing it.
     *
     * @return The unchanged group name
     * @throws IllegalArgumentException if the name does not follow the group naming rules
     */
    @JvmStatic
    fun validate(group: String): String {
        require(namePattern.matches(group)) {
            "Consumer group must contain 1–64 lowercase ASCII letters, digits, dots, underscores or hyphens, " +
                "starting with a letter or digit"
        }
        return group
    }
}
