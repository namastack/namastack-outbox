package io.namastack.outbox.serializer

import io.namastack.outbox.OutboxPayloadSerializer
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes payload classes to their configured [OutboxPayloadSerializer], falling back to the
 * global default when no per-type serializer is registered.
 *
 * The mapping is built at application startup by [OutboxSerializerRegistrar] and is immutable
 * after construction. A [ConcurrentHashMap] cache avoids repeated map lookups for hot paths.
 *
 * Context maps (the `context` field on outbox records) are always serialized with [default]
 * because the context map type (`Map<String, String>`) carries no `@OutboxEvent` annotation.
 */
class OutboxPayloadSerializerRegistry(
    val default: OutboxPayloadSerializer,
    private val serializersByType: Map<Class<*>, OutboxPayloadSerializer>,
) {
    private val cache = ConcurrentHashMap<Class<*>, OutboxPayloadSerializer>()

    fun forType(clazz: Class<*>): OutboxPayloadSerializer =
        cache.getOrPut(clazz) { serializersByType[clazz] ?: default }
}
