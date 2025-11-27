package io.namastack.outbox.instance

/**
 * Enumeration of possible instance statuses.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
enum class OutboxInstanceStatus {
    /**
     * Instance is active and processing records.
     */
    ACTIVE,

    /**
     * Instance is shutting down gracefully.
     */
    SHUTTING_DOWN,

    /**
     * Instance is considered dead (no heartbeat).
     */
    DEAD,
}
