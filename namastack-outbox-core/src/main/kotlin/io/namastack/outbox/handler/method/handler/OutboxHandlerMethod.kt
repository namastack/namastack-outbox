package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.ReflectionUtils
import io.namastack.outbox.handler.method.InvocableHandlerMethod
import java.lang.reflect.Method

/**
 * Sealed base for regular outbox handler methods.
 *
 * Subclasses: [TypedHandlerMethod] for specific types, [GenericHandlerMethod] for any type.
 *
 * @param bean Bean containing the handler method
 * @param method Handler method for reflection
 * @param canonicalId Stable routing ID, or `null` to use the generated method ID
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
sealed class OutboxHandlerMethod(
    bean: Any,
    method: Method,
    canonicalId: String? = null,
    routingAliases: Set<String> = emptySet(),
) : InvocableHandlerMethod(bean, method) {
    /** Canonical ID persisted on newly scheduled records. */
    val id: String = canonicalId ?: generatedId(bean, method)

    /** Alternative routing IDs accepted for records persisted under an earlier identity. */
    val aliases: Set<String> = routingAliases - id

    internal companion object {
        fun generatedId(
            bean: Any,
            method: Method,
            targetClass: Class<*> = ReflectionUtils.getTargetClass(bean),
        ): String {
            val parameterTypes = method.parameterTypes.joinToString(",") { it.name }
            return "${targetClass.name}#${method.name}($parameterTypes)"
        }
    }

    /** Invokes the primary handler with the deserialized payload and record metadata. */
    abstract fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    )
}
