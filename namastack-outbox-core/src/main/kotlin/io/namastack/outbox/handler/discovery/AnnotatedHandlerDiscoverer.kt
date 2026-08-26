package io.namastack.outbox.handler.discovery

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.ReflectionUtils
import org.springframework.core.annotation.AnnotatedElementUtils

/**
 * Discovers primary methods declared with [OutboxHandler] and retains their routing configuration.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object AnnotatedHandlerDiscoverer {
    /** Returns annotated candidates and their configured routing identity. */
    fun discover(
        bean: Any,
        beanName: String,
    ): List<HandlerCandidate> =
        ReflectionUtils
            .findAnnotatedMethods(bean, OutboxHandler::class.java)
            .map { method ->
                val annotation = AnnotatedElementUtils.findMergedAnnotation(method, OutboxHandler::class.java)
                checkNotNull(annotation)

                HandlerCandidate(
                    beanName = beanName,
                    bean = bean,
                    method = method,
                    source = HandlerSource.ANNOTATION,
                    configuredId = annotation.id.takeIf { it.isNotEmpty() },
                    configuredAliases = annotation.aliases.toSet(),
                    lambdaBeanNameId = null,
                    supportsPayload = { _, _ -> true },
                )
            }.toList()
}
