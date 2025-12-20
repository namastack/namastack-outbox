package io.namastack.outbox.handler.registry

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod

/**
 * Registry that stores and retrieves fallback handler methods with a 1:1 mapping to their corresponding handlers.
 *
 * Each handler can have at most one fallback handler, stored by the handler's unique ID.
 * This ensures that when a handler fails after retry exhaustion, the correct fallback is invoked.
 *
 * Fallback handlers are registered from two sources:
 * 1. @OutboxFallbackHandler annotated methods (discovered by AnnotatedFallbackHandlerScanner)
 * 2. OutboxHandlerWithFallback/OutboxTypedHandlerWithFallback interface implementations (discovered by InterfaceFallbackHandlerScanner)
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class OutboxFallbackHandlerRegistry {
    /**
     * Map of fallback handlers indexed by their corresponding handler's unique ID.
     *
     * Maintains a strict 1:1 relationship between handlers and fallback handlers.
     * When a handler fails after all retries are exhausted, this map is used to find
     * the specific fallback handler to invoke using the handler's ID from metadata.handlerId.
     */
    private val fallbackHandlersByHandlerId = mutableMapOf<String, OutboxFallbackHandlerMethod>()

    /**
     * Retrieves the fallback handler for a specific handler by its unique ID.
     *
     * Used by the outbox processing scheduler to find the fallback handler to invoke
     * when a handler fails after all retry attempts are exhausted or when a non-retryable
     * exception occurs.
     *
     * @param id The unique handler ID (from metadata.handlerId)
     * @return The fallback handler for this handler, or null if no fallback is registered
     */
    fun getHandlerById(id: String): OutboxFallbackHandlerMethod? = fallbackHandlersByHandlerId[id]

    /**
     * Registers a fallback handler for a specific handler.
     *
     * Establishes a 1:1 relationship between a handler and its fallback handler.
     * Each handler can have at most one fallback handler. If a fallback is already
     * registered for the given handler ID, an IllegalStateException is thrown.
     *
     * The fallback handler will be invoked when:
     * - The handler has exhausted all retry attempts (failureCount > maxRetries)
     * - The handler threw a non-retryable exception according to the retry policy
     *
     * @param handlerId The unique ID of the handler that this fallback belongs to
     * @param fallbackHandlerMethod The fallback handler method to register
     * @throws IllegalStateException if a fallback handler is already registered for this handler ID
     */
    internal fun register(
        handlerId: String,
        fallbackHandlerMethod: OutboxFallbackHandlerMethod,
    ) {
        check(fallbackHandlersByHandlerId.putIfAbsent(handlerId, fallbackHandlerMethod) == null) {
            "Multiple fallback handlers for handler ID detected: $handlerId"
        }
    }
}
