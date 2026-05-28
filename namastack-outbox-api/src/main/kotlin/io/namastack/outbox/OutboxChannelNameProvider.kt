package io.namastack.outbox

/**
 * Provides the logical channel name for the current outbox runtime.
 *
 * The channel name is a general-purpose identifier that can be used across
 * the outbox infrastructure — for example in observability (metrics, tracing),
 * logging, or any other component that needs to distinguish between channels.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
fun interface OutboxChannelNameProvider {
    /**
     * Returns the logical channel name for the current outbox runtime.
     */
    fun getChannelName(): String

    companion object {
        const val DEFAULT_CHANNEL = "default"

        /** Default provider that always returns [DEFAULT_CHANNEL]. */
        val DEFAULT: OutboxChannelNameProvider = OutboxChannelNameProvider { DEFAULT_CHANNEL }
    }
}
