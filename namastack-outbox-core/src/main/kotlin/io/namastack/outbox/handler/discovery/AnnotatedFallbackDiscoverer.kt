package io.namastack.outbox.handler.discovery

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.handler.ReflectionUtils

/**
 * Discovers fallback methods declared with [OutboxFallbackHandler].
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object AnnotatedFallbackDiscoverer {
    /**
     * Discovers methods on a bean that carry [OutboxFallbackHandler].
     *
     * @param bean Bean to inspect for annotated fallback methods
     * @return Unvalidated fallback declarations in Spring's introspection order
     */
    fun discover(bean: Any): List<FallbackCandidate> =
        ReflectionUtils
            .findAnnotatedMethods(bean, OutboxFallbackHandler::class.java)
            .map { method ->
                FallbackCandidate(
                    bean = bean,
                    method = method,
                    payloadType = method.parameterTypes.firstOrNull(),
                    source = HandlerSource.ANNOTATION,
                )
            }.toList()
}
