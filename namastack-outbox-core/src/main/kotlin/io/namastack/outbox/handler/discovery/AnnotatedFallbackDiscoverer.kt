package io.namastack.outbox.handler.discovery

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.handler.ReflectionUtils
import org.springframework.core.annotation.AnnotatedElementUtils

/**
 * Discovers fallback methods declared with [OutboxFallbackHandler].
 *
 * @author Roland Beisel
 * @since 1.9.0
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
                val annotation = AnnotatedElementUtils.findMergedAnnotation(method, OutboxFallbackHandler::class.java)
                checkNotNull(annotation)

                FallbackCandidate(
                    bean = bean,
                    method = method,
                    payloadType = method.parameterTypes.firstOrNull(),
                    source = HandlerSource.ANNOTATION,
                    disposition = annotation.disposition,
                )
            }.toList()
}
