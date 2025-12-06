package io.namastack.outbox.handler.method

import java.lang.reflect.Method

/**
 * Factory interface for creating OutboxHandlerMethod instances from methods.
 *
 * Different implementations handle different method signatures:
 * - [TypedHandlerMethodFactory]: For single-parameter typed handlers
 * - [GenericHandlerMethodFactory]: For two-parameter generic handlers
 *
 * The factory pattern allows flexible handler method discovery and validation
 * before creating the actual handler method wrappers.
 */
interface OutboxHandlerMethodFactory {
    /**
     * Checks if this factory can handle the given method signature.
     *
     * Each factory implementation supports a specific handler signature.
     * This method is called to determine which factory should create the handler.
     *
     * @param method The method to check
     * @return true if this factory can create a handler from this method
     */
    fun supports(method: Method): Boolean

    /**
     * Creates an OutboxHandlerMethod wrapper from a bean method.
     *
     * Validates the method signature and creates the appropriate handler method
     * subclass. The factory must have already confirmed support via [supports()].
     *
     * @param bean The bean instance containing the handler method
     * @param method The method to wrap as a handler
     * @return Properly typed OutboxHandlerMethod subclass
     * @throws IllegalArgumentException if method signature is invalid
     */
    fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod
}
