package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import java.lang.reflect.Method

/**
 * Factory interface for creating fallback handler method wrappers.
 *
 * Implementations: [TypedFallbackHandlerMethodFactory], [GenericFallbackHandlerMethodFactory]
 */
interface OutboxFallbackHandlerMethodFactory {
    /**
     * Checks if this factory supports the given method signature.
     *
     * @param method Method to validate
     * @return true if this factory can create a handler from this method
     */
    fun supports(method: Method): Boolean

    /**
     * Creates fallback handler method wrapper. Call after confirming support.
     *
     * @param bean Bean containing the fallback method
     * @param method Fallback method to wrap
     * @return Typed or generic fallback handler wrapper
     * @throws IllegalArgumentException if signature is invalid
     */
    fun create(
        bean: Any,
        method: Method,
    ): OutboxFallbackHandlerMethod
}
