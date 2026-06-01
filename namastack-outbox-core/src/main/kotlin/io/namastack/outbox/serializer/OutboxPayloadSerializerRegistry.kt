package io.namastack.outbox.serializer

import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.annotation.OutboxEvent
import org.springframework.core.annotation.AnnotationUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes payload classes to their configured [OutboxPayloadSerializer], falling back to the
 * global default when no per-type serializer is declared.
 *
 * Resolution is lazy: on first call for a given class, the [OutboxEvent.serializer] attribute
 * is read and the serializer is instantiated via its public no-arg constructor. The result is
 * cached permanently so reflection only happens once per payload type.
 *
 * Context maps (`Map<String, String>`) carry no [OutboxEvent] annotation and always use [default].
 */
class OutboxPayloadSerializerRegistry(
    val default: OutboxPayloadSerializer,
) {
    private val cache = ConcurrentHashMap<Class<*>, OutboxPayloadSerializer>()

    fun forType(clazz: Class<*>): OutboxPayloadSerializer = cache.computeIfAbsent(clazz) { resolve(it) }

    private fun resolve(clazz: Class<*>): OutboxPayloadSerializer {
        val serializerClass =
            AnnotationUtils
                .findAnnotation(clazz, OutboxEvent::class.java)
                ?.serializer
                ?.takeIf { it != OutboxPayloadSerializer::class }
                ?: return default

        return try {
            @Suppress("UNCHECKED_CAST")
            serializerClass.java.getDeclaredConstructor().newInstance() as OutboxPayloadSerializer
        } catch (e: Exception) {
            throw IllegalStateException(
                "@OutboxEvent on ${clazz.name} specifies serializer ${serializerClass.qualifiedName} " +
                    "but it could not be instantiated. Ensure it has a public no-arg constructor.",
                e,
            )
        }
    }
}
