package io.namastack.outbox.routing

/**
 * Header names shared by outbox messaging integrations.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
object OutboxHeaders {
    /**
     * Consumer-facing idempotency key. Defaults to the persisted record ID when enabled.
     *
     * Configure a route header with this name to override the default using payload or context data.
     * The value must remain stable across retries and must not be empty or whitespace-only.
     */
    const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
}
