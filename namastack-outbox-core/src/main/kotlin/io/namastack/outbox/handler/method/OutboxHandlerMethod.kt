package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.OutboxHandlerRegistry
import java.lang.reflect.Method

/**
 * Sealed base class for all outbox handler methods.
 *
 * Represents a handler method with reflection metadata. Subclasses define
 * specific handler signatures:
 * - [TypedHandlerMethod]: Single parameter with specific type
 * - [GenericHandlerMethod]: Two parameters (Any + OutboxRecordMetadata)
 *
 * Each handler method has a unique [id] derived from its class name,
 * method name, and parameter types. This id is used for tracking and routing.
 *
 * @param bean The bean instance containing the handler method
 * @param method The actual Java Method object for reflection
 */
sealed class OutboxHandlerMethod(
    val bean: Any,
    val method: Method,
) {
    /**
     * Unique identifier for this handler method.
     *
     * Format: `className#methodName(paramType1,paramType2,...)`
     *
     * Example: `com.example.OrderHandler#handle(com.example.OrderCreated)`
     */
    val id: String = buildId()

    /**
     * Registers this handler method with the given registry.
     *
     * @param registry The handler registry to register with
     */
    abstract fun register(registry: OutboxHandlerRegistry)

    /**
     * Builds a unique identifier for this handler method.
     *
     * Combines the bean's fully qualified class name, method name,
     * and all parameter types to create a unique identifier.
     *
     * This id is used to:
     * - Track handler registrations (detect duplicates)
     * - Route records to their handlers via metadata
     * - Enable handler lookup by id
     *
     * @return Unique handler method identifier
     */
    protected fun buildId(): String {
        val className = bean::class.java.name
        val methodName = method.name

        val paramTypes = method.parameterTypes.joinToString(",") { it.name }

        return "$className#$methodName($paramTypes)"
    }
}
