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
 * @param canonicalId Stable routing ID resolved for this handler
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
sealed class OutboxHandlerMethod(
    bean: Any,
    method: Method,
    canonicalId: String,
    routingAliases: Set<String> = emptySet(),
) : InvocableHandlerMethod(bean, method) {
    /** Canonical ID persisted on newly scheduled records. */
    val id: String = canonicalId

    /** Alternative routing IDs accepted for records persisted under an earlier identity. */
    val aliases: Set<String> = routingAliases - id

    /**
     * Invokes the primary handler with a deserialized payload and record metadata.
     *
     * @param payload Deserialized record payload
     * @param metadata Metadata of the record being processed
     * @throws Throwable The original exception raised by the handler
     */
    abstract fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    )

    internal companion object {
        /**
         * Generates the class-and-method identity used when no stable ID is configured.
         *
         * @param bean Bean that owns the handler method
         * @param method Reflected handler method
         * @param targetClass Class name to include in the generated identity
         * @return An identity containing the class, method, and parameter type names
         */
        fun generatedId(
            bean: Any,
            method: Method,
            targetClass: Class<*> = ReflectionUtils.getTargetClass(bean),
        ): String {
            val parameterTypes = method.parameterTypes.joinToString(",") { it.name }
            return "${targetClass.name}#${method.name}($parameterTypes)"
        }
    }
}
