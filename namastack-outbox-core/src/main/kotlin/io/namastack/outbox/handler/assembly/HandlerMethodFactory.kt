package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.handler.identity.HandlerIdentity
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import java.lang.reflect.Method

/**
 * Creates invocable method wrappers from validated handler declarations.
 *
 * Primary declarations are represented as typed or generic handlers according to their payload
 * parameter. Fallback declarations share a single invocation wrapper.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object HandlerMethodFactory {
    /** Creates a typed or generic primary method according to the declared payload type. */
    fun primary(
        candidate: HandlerCandidate,
        identity: HandlerIdentity,
    ): OutboxHandlerMethod =
        if (candidate.method.parameterTypes.first() == Any::class.java) {
            GenericHandlerMethod(
                candidate.bean,
                candidate.method,
                candidate.supportsScheduling,
                identity.canonicalId,
                identity.aliases,
            )
        } else {
            TypedHandlerMethod(candidate.bean, candidate.method, identity.canonicalId, identity.aliases)
        }

    /** Creates the common method wrapper used by typed and generic fallbacks. */
    fun fallback(
        bean: Any,
        method: Method,
    ) = OutboxFallbackHandlerMethod(bean, method)
}
