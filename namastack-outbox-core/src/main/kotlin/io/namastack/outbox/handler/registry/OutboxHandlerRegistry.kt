package io.namastack.outbox.handler.registry

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.assembly.HandlerRegistration
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.selection.OutboxHandlerSelector
import kotlin.reflect.KClass

/**
 * Registry that stores and retrieves handler methods.
 *
 * Maintains three separate indexes for efficient handler lookup:
 * - By ID: Direct lookup by handler method's unique identifier
 * - By Payload Type: Lookup typed handlers for a specific payload type
 * - Generic Handlers: List of handlers that accept any payload type
 *
 * Complete registrations are assembled from annotation and interface declarations before
 * canonical and alias routes are installed as one validated batch.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class OutboxHandlerRegistry {
    /**
     * Map of all handlers indexed by their unique ID.
     * Used for direct handler lookup via metadata.handlerId.
     */
    private val handlersById = mutableMapOf<String, OutboxHandlerMethod>()
    private val registrationsById = mutableMapOf<String, HandlerRegistration>()

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

    internal fun getRegistrationById(id: String): HandlerRegistration? = registrationsById[id]

    /**
     * Returns descriptors for all primary registered handlers.
     *
     * Legacy aliases are intentionally excluded.
     *
     * @return registered handler descriptors sorted by handler id
     */
    fun findAllHandlerDescriptors(): List<OutboxHandlerDescriptor> =
        (typedHandlers.values.flatten() + genericHandlers)
            .map { OutboxHandlerDescriptorFactory.create(it) }
            .sortedBy { it.id }

    /**
     * Returns a descriptor for a handler id or legacy alias.
     *
     * @param id stable handler id or legacy alias id
     * @return handler descriptor, or null when no handler is registered for the id
     */
    fun findHandlerDescriptorById(id: String): OutboxHandlerDescriptor? =
        handlersById[id]?.let(OutboxHandlerDescriptorFactory::create)

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
        OutboxHandlerSelector.typed(typedHandlers, type)

    /**
     * Retrieves all registered generic handlers.
     *
     * Generic handlers are invoked for all records as a fallback.
     * They complement typed handlers and receive full metadata context.
     *
     * @return Copy of generic handlers list
     */
    fun getGenericHandlers(
        payload: Any,
        metadataProvider: (GenericHandlerMethod) -> OutboxRecordMetadata,
    ): List<GenericHandlerMethod> = OutboxHandlerSelector.generic(genericHandlers, payload, metadataProvider)

    /** Atomically installs canonical and alias routes plus scheduling indexes. */
    @Synchronized
    internal fun registerBatch(registrations: List<HandlerRegistration>) {
        if (registrations.isEmpty()) return
        validateCompleteRegistrationBatch(registrations)

        registrations.forEach { registration ->
            val handler = registration.primary
            when (handler) {
                is TypedHandlerMethod ->
                    typedHandlers
                        .computeIfAbsent(
                            handler.paramType,
                        ) { mutableListOf() }
                        .add(handler)

                is GenericHandlerMethod -> genericHandlers.add(handler)
            }
            (sequenceOf(handler.id) + handler.aliases.asSequence()).forEach { routingId ->
                handlersById[routingId] = handler
                registrationsById[routingId] = registration
            }
        }
    }

    private fun validateCompleteRegistrationBatch(registrations: List<HandlerRegistration>) {
        val proposed = mutableMapOf<String, RoutingOwner>()
        registrations.forEach { registration ->
            val handler = registration.primary
            (sequenceOf(handler.id to "canonical") + handler.aliases.asSequence().map { it to "alias" })
                .forEach { (routingId, role) ->
                    val incoming = RoutingOwner(role, registration.beanName, handler)
                    val conflicting = proposed[routingId] ?: routingOwner(routingId)
                    check(conflicting == null) { collisionMessage(routingId, conflicting!!, incoming) }
                    proposed[routingId] = incoming
                }
        }
    }

    private fun routingOwner(routingId: String): RoutingOwner? =
        registrationsById[routingId]?.let { registration ->
            val handler = registration.primary
            val role = if (routingId == handler.id) "canonical" else "alias"

            RoutingOwner(role, registration.beanName, handler)
        }

    private fun collisionMessage(
        routingId: String,
        first: RoutingOwner,
        second: RoutingOwner,
    ): String =
        "duplicate handler routing ID collision for '$routingId': " +
            "${first.role} on bean '${first.beanName}' method " +
            "${first.handler.method.toGenericString()} conflicts with " +
            "${second.role} on bean '${second.beanName}' method ${second.handler.method.toGenericString()}"
}
