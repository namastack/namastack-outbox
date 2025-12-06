package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Factory for creating generic handler methods from annotated methods or interface implementations.
 *
 * A generic handler method has the signature:
 * ```
 * fun handle(payload: Any, metadata: OutboxRecordMetadata)
 * ```
 *
 * Generic handlers receive all outbox records regardless of payload type,
 * enabling multi-type processing logic with access to record metadata.
 */
class GenericHandlerMethodFactory : OutboxHandlerMethodFactory {
    /**
     * Checks if a method is a valid generic handler method.
     *
     * Requirements:
     * - Exactly 2 parameters
     * - First parameter type must be `Any` (accepts any payload)
     * - Second parameter type must be `OutboxRecordMetadata`
     *
     * @param method The method to check
     * @return true if method matches generic handler signature
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 2) return false

        val payloadType = method.parameterTypes[0].kotlin
        val metadataType = method.parameterTypes[1].kotlin

        return payloadType == Any::class && metadataType == OutboxRecordMetadata::class
    }

    /**
     * Creates a generic handler method from an annotated method.
     *
     * Validates that the method signature is correct before creating the handler.
     *
     * Example:
     * ```kotlin
     * @OutboxHandler
     * fun handle(payload: Any, metadata: OutboxRecordMetadata) { ... }
     * ```
     *
     * @param bean The bean instance containing the handler method
     * @param method The method to wrap as a handler
     * @return GenericHandlerMethod ready for invocation
     * @throws IllegalArgumentException if method signature is invalid
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod {
        val payloadType = method.parameterTypes[0].kotlin
        val metadataType = method.parameterTypes[1].kotlin

        require(payloadType == Any::class) {
            "Generic @OutboxHandler first parameter must be Any: $method"
        }
        require(metadataType == OutboxRecordMetadata::class) {
            "Generic @OutboxHandler second parameter must be OutboxRecordMetadata: $method"
        }

        return GenericHandlerMethod(bean, method)
    }

    /**
     * Creates a generic handler method from an OutboxHandler interface implementation.
     *
     * Discovers the `handle(Any, OutboxRecordMetadata)` method from the interface
     * and wraps it as a GenericHandlerMethod.
     *
     * This is used when handlers are registered via interface implementation instead
     * of annotation-based method discovery.
     *
     * Example:
     * ```kotlin
     * @Component
     * class MyHandler : OutboxHandler {
     *     override fun handle(payload: Any, metadata: OutboxRecordMetadata) { ... }
     * }
     * ```
     *
     * @param bean The OutboxHandler interface implementation
     * @return GenericHandlerMethod wrapping the handle method
     * @throws NoSuchElementException if the handle method cannot be found
     */
    fun createFromInterface(bean: OutboxHandler): GenericHandlerMethod {
        val method =
            bean::class.java.methods.first {
                it.name == "handle" && it.parameterCount == 2
            }

        return GenericHandlerMethod(bean, method)
    }
}
