package io.namastack.outbox.handler.registry

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod

/**
 * Registry that stores and retrieves fallback handler methods with a 1:1 mapping to their corresponding handlers.
 *
 * This compatibility facade keeps the processor boundary unchanged while reading fallbacks from
 * complete registrations owned by [OutboxHandlerRegistry]. Canonical IDs and aliases therefore
 * always resolve through the same routing source.
 *
 * @param handlerRegistry Registry owning the complete handler registrations
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class OutboxFallbackHandlerRegistry internal constructor(
    private val handlerRegistry: OutboxHandlerRegistry,
) {
    /**
     * Checks whether a fallback handler is registered for a canonical ID or alias.
     *
     * @param id Canonical handler ID or lookup alias
     * @return `true` if a fallback handler is registered for the given ID, `false` otherwise
     */
    fun existsByHandlerId(id: String): Boolean = getByHandlerId(id) != null

    /**
     * Retrieves the fallback handler for a canonical ID or alias.
     *
     * Used by the outbox processing scheduler to find the fallback handler to invoke
     * when a handler fails after all retry attempts are exhausted or when a non-retryable
     * exception occurs.
     *
     * @param id Canonical handler ID or lookup alias
     * @return The fallback handler for this handler, or null if no fallback is registered
     */
    fun getByHandlerId(id: String): OutboxFallbackHandlerMethod? = handlerRegistry.getRegistrationById(id)?.fallback
}
