package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.method.BaseHandlerMethod
import java.lang.reflect.Method

/**
 * Sealed base for regular outbox handler methods.
 *
 * Subclasses: [TypedHandlerMethod] for specific types, [GenericHandlerMethod] for any type.
 *
 * @param bean Bean containing the handler method
 * @param method Handler method for reflection
 */
sealed class OutboxHandlerMethod(
    bean: Any,
    method: Method,
) : BaseHandlerMethod(bean, method)
