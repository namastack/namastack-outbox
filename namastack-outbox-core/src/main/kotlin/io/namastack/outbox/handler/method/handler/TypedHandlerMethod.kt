package io.namastack.outbox.handler.method.handler

import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Handler for records with a specific payload type. Provides type-safe processing.
 *
 * Example: `fun handle(payload: OrderCreatedEvent)`
 *
 * @param bean Bean containing the handler method
 * @param method Handler method (must have single typed parameter)
 */
class TypedHandlerMethod(
    bean: Any,
    method: Method,
) : OutboxHandlerMethod(bean, method) {
    /** Payload type extracted from method's first parameter. */
    internal val paramType: KClass<*>
        get() = method.parameterTypes.first().kotlin

    /**
     * Invokes handler with typed payload via reflection.
     *
     * @param payload Record payload matching paramType
     * @throws Throwable Original exception from handler (triggers retry logic)
     */
    fun invoke(payload: Any) = invokeMethod(payload)
}
