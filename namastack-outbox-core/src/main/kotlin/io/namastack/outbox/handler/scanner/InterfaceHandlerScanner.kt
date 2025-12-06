package io.namastack.outbox.handler.scanner

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.method.GenericHandlerMethodFactory
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethodFactory

/**
 * Scanner that discovers handler implementations via interface inheritance.
 *
 * Finds beans that implement:
 * - [OutboxTypedHandler]: Typed handler for a specific payload type T
 * - [OutboxHandler]: Generic handler for any payload type
 *
 * This scanner complements [AnnotatedHandlerScanner] for handlers that use
 * interface-based registration instead of @OutboxHandler annotations.
 *
 * A bean can implement both interfaces and will register both handlers.
 */
class InterfaceHandlerScanner : HandlerScanner {
    /**
     * Scans a bean for OutboxHandler and OutboxTypedHandler interface implementations.
     *
     * Algorithm:
     * 1. Check if bean implements OutboxTypedHandler<T> or OutboxHandler
     * 2. If not, return empty list (no handlers to register)
     * 3. If OutboxTypedHandler<T>: Extract generic type T and create typed handler
     * 4. If OutboxHandler: Create generic handler accepting Any payload
     * 5. Return all discovered handlers (bean can implement both interfaces)
     *
     * Example:
     * ```kotlin
     * @Component
     * class OrderHandler : OutboxTypedHandler<OrderCreatedEvent> {
     *     override fun handle(payload: OrderCreatedEvent) { ... }
     * }
     *
     * @Component
     * class UniversalHandler : OutboxHandler {
     *     override fun handle(payload: Any, metadata: OutboxRecordMetadata) { ... }
     * }
     *
     * @Component
     * class HybridHandler : OutboxTypedHandler<OrderCreated>, OutboxHandler {
     *     // Both handlers will be registered
     * }
     * ```
     *
     * @param bean The bean to scan for handler interface implementations
     * @return List of discovered OutboxHandlerMethod instances
     */
    override fun scan(bean: Any): List<OutboxHandlerMethod> {
        if (bean !is OutboxTypedHandler<*> && bean !is OutboxHandler) return emptyList()

        val handlers = mutableListOf<OutboxHandlerMethod>()

        // Register typed handler if bean implements OutboxTypedHandler<T>
        if (bean is OutboxTypedHandler<*>) {
            handlers += TypedHandlerMethodFactory().createFromInterface(bean)
        }

        // Register generic handler if bean implements OutboxHandler
        if (bean is OutboxHandler) {
            handlers += GenericHandlerMethodFactory().createFromInterface(bean)
        }

        return handlers
    }
}
