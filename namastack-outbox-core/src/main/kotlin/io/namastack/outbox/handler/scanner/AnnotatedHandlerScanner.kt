package io.namastack.outbox.handler.scanner

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.OutboxHandlerMethodFactory

/**
 * Scanner that discovers @OutboxHandler annotated methods in beans.
 *
 * Finds all methods marked with @OutboxHandler and uses the appropriate
 * factory to create handler method wrappers based on method signature.
 *
 * Supports both:
 * - Typed handlers: Single parameter with specific type
 * - Generic handlers: Two parameters (Any + OutboxRecordMetadata)
 *
 * @param factories List of handler method factories for creating handler wrappers
 */
class AnnotatedHandlerScanner(
    private val factories: List<OutboxHandlerMethodFactory>,
) : HandlerScanner {
    /**
     * Scans a bean for @OutboxHandler annotated methods.
     *
     * Processes all methods in the bean's class, filters those with @OutboxHandler annotation,
     * and uses the appropriate factory to create OutboxHandlerMethod instances.
     *
     * Algorithm:
     * 1. Get all methods from bean's class
     * 2. Filter methods that have @OutboxHandler annotation
     * 3. For each annotated method:
     *    a. Find the first factory that supports this method's signature
     *    b. Use the factory to create an OutboxHandlerMethod
     * 4. Return all discovered handler methods
     *
     * If a method's signature doesn't match any factory's supports() check,
     * it is skipped (mapNotNull behavior).
     *
     * @param bean The bean to scan for @OutboxHandler methods
     * @return List of discovered OutboxHandlerMethod instances
     */
    override fun scan(bean: Any): List<OutboxHandlerMethod> =
        bean::class.java.methods
            .filter { it.isAnnotationPresent(OutboxHandler::class.java) }
            .mapNotNull { method ->
                factories.firstOrNull { it.supports(method) }?.create(bean, method)
            }
}
