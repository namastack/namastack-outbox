package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.handler.discovery.HandlerSource
import io.namastack.outbox.handler.identity.HandlerIdentity
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import java.lang.reflect.Method

/**
 * Creates invocable method wrappers from validated handler declarations.
 *
 * Interface declarations retain their typed or generic contract. Annotation declarations are
 * classified by their payload parameter. Fallback declarations share a single invocation wrapper.
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal object HandlerMethodFactory {
    /**
     * Creates an invocable primary handler from a validated declaration.
     *
     * @param candidate Validated handler declaration
     * @param identity Canonical ID and aliases assigned to the handler
     * @return A generic or typed handler method matching the declaration contract
     */
    fun primary(
        candidate: HandlerCandidate,
        identity: HandlerIdentity,
    ): OutboxHandlerMethod =
        if (isGeneric(candidate)) {
            GenericHandlerMethod(
                bean = candidate.bean,
                method = candidate.method,
                canonicalId = identity.canonicalId,
                payloadSupport = candidate.supportsPayload,
                routingAliases = identity.aliases,
            )
        } else {
            TypedHandlerMethod(
                bean = candidate.bean,
                method = candidate.method,
                canonicalId = identity.canonicalId,
                payloadType = checkNotNull(candidate.payloadType).kotlin,
                routingAliases = identity.aliases,
            )
        }

    /**
     * Creates the invocation wrapper shared by typed and generic fallback declarations.
     *
     * @param bean Bean that owns the fallback method
     * @param method Reflected fallback method
     * @return An invocable fallback handler method
     */
    fun fallback(
        bean: Any,
        method: Method,
    ) = OutboxFallbackHandlerMethod(bean, method)

    private fun isGeneric(candidate: HandlerCandidate): Boolean =
        when (candidate.source) {
            HandlerSource.GENERIC_INTERFACE -> true
            HandlerSource.TYPED_INTERFACE -> false
            HandlerSource.ANNOTATION -> candidate.method.parameterTypes.first() == Any::class.java
        }
}
