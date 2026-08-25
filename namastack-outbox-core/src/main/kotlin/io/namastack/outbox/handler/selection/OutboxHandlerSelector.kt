package io.namastack.outbox.handler.selection

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import kotlin.reflect.KClass

/**
 * Performs side-effect-free selection from the registry's scheduling indexes.
 *
 * Typed selection returns a defensive copy. Generic selection evaluates each handler's scheduling
 * predicate against handler-specific metadata.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object OutboxHandlerSelector {
    /** Returns a defensive copy of handlers registered for [type]. */
    fun typed(
        handlers: Map<KClass<*>, List<TypedHandlerMethod>>,
        type: KClass<*>,
    ): List<TypedHandlerMethod> = handlers[type]?.toList() ?: emptyList()

    /** Returns generic handlers whose scheduling predicate accepts the record. */
    fun generic(
        handlers: List<GenericHandlerMethod>,
        payload: Any,
        metadataProvider: (GenericHandlerMethod) -> OutboxRecordMetadata,
    ): List<GenericHandlerMethod> = handlers.filter { it.supportsScheduling(payload, metadataProvider(it)) }
}
