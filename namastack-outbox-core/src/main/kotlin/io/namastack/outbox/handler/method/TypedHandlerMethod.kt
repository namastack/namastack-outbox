package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.OutboxHandlerRegistry
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Represents a typed handler method that processes outbox records with a specific payload type.
 *
 * Typed handlers accept a single parameter of a specific type [paramType].
 * This enables type-safe processing without runtime type checking.
 *
 * Example:
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: OrderCreatedEvent) { ... }
 * ```
 *
 * Typed handlers are only invoked when the record payload type matches [paramType].
 * They take precedence over generic handlers when both are registered.
 *
 * @param bean The bean instance containing the handler method
 * @param method The actual method to invoke (reflection)
 * @param paramType The specific payload type this handler processes
 */
class TypedHandlerMethod(
    bean: Any,
    method: Method,
    val paramType: KClass<*>,
) : OutboxHandlerMethod(bean, method) {
    /**
     * Invokes the typed handler method with the given payload.
     *
     * Safely invokes the underlying method via reflection, passing the payload
     * as the single parameter.
     *
     * If the handler method throws an exception, the original exception is unwrapped
     * from the InvocationTargetException and rethrown.
     *
     * Example:
     * ```kotlin
     * val handler = TypedHandlerMethod(myBean, myHandleMethod, OrderCreatedEvent::class)
     * handler.invoke(OrderCreated(orderId = "123"))
     * ```
     *
     * @param payload The record payload to process
     * @throws Exception the original exception thrown by the handler method (will trigger retries)
     */
    fun invoke(payload: Any) {
        try {
            method.invoke(bean, payload)
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            throw ex.targetException
        }
    }

    /**
     * Registers this typed handler with the given registry.
     *
     * The registry will route records with matching payload types to this handler.
     *
     * @param registry The handler registry to register with
     */
    override fun register(registry: OutboxHandlerRegistry) {
        registry.registerTypedHandler(
            handlerMethod = this,
            paramType = paramType,
        )
    }
}
