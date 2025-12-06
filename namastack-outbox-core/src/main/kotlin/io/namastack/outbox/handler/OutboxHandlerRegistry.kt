package io.namastack.outbox.handler

import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethod
import kotlin.reflect.KClass

/**
 * Registry that stores and retrieves handler methods.
 *
 * Maintains three separate indexes for efficient handler lookup:
 * - By ID: Direct lookup by handler method's unique identifier
 * - By Payload Type: Lookup typed handlers for a specific payload type
 * - Generic Handlers: List of handlers that accept any payload type
 *
 * Handlers are registered from two sources:
 * 1. @OutboxHandler annotated methods (discovered by AnnotatedHandlerScanner)
 * 2. OutboxHandler/OutboxTypedHandler interface implementations (discovered by InterfaceHandlerScanner)
 */
class OutboxHandlerRegistry {
    /**
     * Map of all handlers indexed by their unique ID.
     * Used for direct handler lookup via metadata.handlerId.
     */
    private val handlersById = mutableMapOf<String, OutboxHandlerMethod>()

    /**
     * Map of typed handlers indexed by payload type.
     * Multiple typed handlers can be registered for the same type.
     */
    private val typedHandlers = mutableMapOf<KClass<*>, MutableList<TypedHandlerMethod>>()

    /**
     * List of generic handlers that process any payload type.
     * These handlers always have 2 parameters (Any + OutboxRecordMetadata).
     */
    private val genericHandlers = mutableListOf<GenericHandlerMethod>()

    /**
     * Retrieves a handler by its unique ID.
     *
     * Used by OutboxDispatcher to find the specific handler to invoke
     * based on the metadata.handlerId stored with the record.
     *
     * @param id The unique handler method identifier
     * @return The OutboxHandlerMethod, or null if not found
     */
    fun getHandlerById(id: String): OutboxHandlerMethod? = handlersById[id]

    /**
     * Retrieves all typed handlers that match a specific payload type.
     *
     * Returns an empty list if no typed handlers are registered for the type.
     * Multiple handlers can be registered for the same type (all will be invoked).
     *
     * @param type The payload type to search for
     * @return List of TypedHandlerMethods for this type (empty if none)
     */
    fun getHandlersForPayloadType(type: KClass<*>): List<TypedHandlerMethod> =
        typedHandlers[type]?.toList() ?: emptyList()

    /**
     * Retrieves all registered generic handlers.
     *
     * Generic handlers are invoked for all records as a fallback.
     * They complement typed handlers and receive full metadata context.
     *
     * @return Copy of generic handlers list
     */
    fun getGenericHandlers(): List<GenericHandlerMethod> = genericHandlers.toList()

    /**
     * Registers a typed handler for a specific payload type.
     *
     * Also adds the handler to the global handlers map for ID-based lookup.
     *
     * @param handlerMethod The typed handler method to register
     * @param paramType The payload type this handler processes
     * @throws IllegalStateException if a handler with the same ID already exists
     */
    internal fun registerTypedHandler(
        handlerMethod: TypedHandlerMethod,
        paramType: KClass<*>,
    ) {
        typedHandlers
            .computeIfAbsent(paramType) { mutableListOf() }
            .add(handlerMethod)

        registerInAllHandlers(handlerMethod)
    }

    /**
     * Registers a generic handler.
     *
     * Also adds the handler to the global handlers map for ID-based lookup.
     *
     * @param handlerMethod The generic handler method to register
     * @throws IllegalStateException if a handler with the same ID already exists
     */
    internal fun registerGenericHandler(handlerMethod: GenericHandlerMethod) {
        genericHandlers.add(handlerMethod)

        registerInAllHandlers(handlerMethod)
    }

    /**
     * Registers a handler in the global ID map.
     *
     * Ensures no duplicate handler IDs are registered - each handler method
     * must have a unique ID derived from its class, method name, and parameters.
     *
     * @param handlerMethod The handler method to register globally
     * @throws IllegalStateException if a handler with the same ID is already registered
     */
    private fun registerInAllHandlers(handlerMethod: OutboxHandlerMethod) {
        check(handlersById.putIfAbsent(handlerMethod.id, handlerMethod) == null) {
            "Duplicate handler ID detected: ${handlerMethod.id}"
        }
    }
}
