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
    /** Returns fallback candidates declared on [bean]. */
    fun discover(bean: Any): List<FallbackCandidate> =
        ReflectionUtils
            .findAnnotatedMethods(bean, OutboxFallbackHandler::class.java)
            .map { FallbackCandidate(bean, it, HandlerSource.ANNOTATION) }
            .toList()
}
