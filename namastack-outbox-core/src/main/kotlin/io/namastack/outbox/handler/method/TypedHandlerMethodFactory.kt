package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.OutboxTypedHandler
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Factory for creating typed handler methods from annotated methods or interface implementations.
 *
 * A typed handler method has the signature:
 * ```
 * fun handle(payload: T)
 * ```
 *
 * Where T is a specific, non-Any type. Typed handlers are only invoked when
 * the record payload type matches exactly.
 */
class TypedHandlerMethodFactory : OutboxHandlerMethodFactory {
    /**
     * Checks if a method is a valid-typed handler method.
     *
     * Requirements:
     * - Exactly 1 parameter
     * - Parameter type must be specific (NOT Any)
     *
     * @param method The method to check
     * @return true if method matches typed handler signature
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 1) return false

        val paramType = method.parameterTypes.first().kotlin

        return paramType != Any::class
    }

    /**
     * Creates a typed handler method from an annotated method.
     *
     * Validates that the method has a single parameter of a specific type
     * (not Any) before creating the handler.
     *
     * Example:
     * ```kotlin
     * @OutboxHandler
     * fun handle(payload: OrderCreatedEvent) { ... }
     * ```
     *
     * @param bean The bean instance containing the handler method
     * @param method The method to wrap as a handler
     * @return TypedHandlerMethod ready for invocation
     * @throws IllegalArgumentException if method parameter type is Any
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod {
        val paramType = method.parameterTypes.first().kotlin

        require(paramType != Any::class) {
            "Typed @OutboxHandler method must not use Any as payload type: $method"
        }

        return TypedHandlerMethod(bean, method, paramType)
    }

    /**
     * Creates a typed handler method from an OutboxTypedHandler<T> interface implementation.
     *
     * Extracts the generic type parameter T from the interface declaration
     * and creates a TypedHandlerMethod with the correct payload type.
     *
     * This is used when handlers are registered via interface implementation instead
     * of annotation-based method discovery.
     *
     * Example:
     * ```kotlin
     * @Component
     * class OrderHandler : OutboxTypedHandler<OrderCreatedEvent> {
     *     override fun handle(payload: OrderCreatedEvent) { ... }
     * }
     * ```
     *
     * @param bean The OutboxTypedHandler<T> interface implementation
     * @return TypedHandlerMethod with the correct payload type T
     * @throws IllegalStateException if generic type T cannot be resolved
     */
    fun createFromInterface(bean: OutboxTypedHandler<*>): TypedHandlerMethod {
        val payloadType =
            bean::class
                .supertypes
                .first { it.classifier == OutboxTypedHandler::class }
                .arguments
                .first()
                .type
                ?.classifier as? KClass<*>
                ?: throw IllegalStateException("OutboxTypedHandler<T> must specify generic type")

        val method = bean::class.java.methods.first { it.name == "handle" && it.parameterCount == 1 }

        return TypedHandlerMethod(bean, method, payloadType)
    }
}
