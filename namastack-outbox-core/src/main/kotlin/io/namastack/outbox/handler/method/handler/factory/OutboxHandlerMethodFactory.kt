package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import java.lang.reflect.Method

/**
 * Factory for creating handler method wrappers from annotated or interface-based methods.
 *
 * Implementations: [TypedHandlerMethodFactory], [GenericHandlerMethodFactory]
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
interface OutboxHandlerMethodFactory {
    /**
     * Checks if this factory supports the given method signature.
     *
     * @param method Method to validate
     * @return true if this factory can create a handler from this method
     */
    fun supports(method: Method): Boolean

    /**
     * Creates handler method wrapper. Call after confirming support with [supports()].
     *
     * @param bean Bean containing the handler method
     * @param method Handler method to wrap
     * @return Typed or generic handler method wrapper
     * @throws IllegalArgumentException if signature is invalid
     */
    fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod
}
