package io.namastack.outbox.routing

/**
 * Represents a routing destination for an outbox record.
 *
 * A routing target specifies where an event should be published, including:
 * - The target destination (e.g., "kafka:orders-topic" or "amqp:user.events.queue")
 * - An optional key/partition key for message ordering or partitioning
 *
 * Headers are configured separately in EventExternalizationConfiguration.
 *
 * @param target The destination identifier (e.g., topic name, queue name)
 * @param key Optional partition/message key for ordering or distribution
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class RoutingTarget(
    val target: String,
    val key: String? = null,
) {
    /**
     * Creates a new RoutingTarget with the given key.
     *
     * @param key The partition/message key
     * @return A new RoutingTarget instance with updated key
     */
    fun withKey(key: String): RoutingTarget = copy(key = key)

    companion object {
        /**
         * Creates a RoutingTarget with only a target.
         */
        @JvmStatic
        fun forTarget(target: String): RoutingTarget = RoutingTarget(target = target)
    }
}
