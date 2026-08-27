package io.namastack.outbox.handler.discovery

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.ReflectionUtils
import org.springframework.core.annotation.AnnotatedElementUtils

/**
 * Discovers primary methods declared with [OutboxHandler] and retains their routing configuration.
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal object AnnotatedHandlerDiscoverer {
    /**
     * Discovers methods on a bean that carry [OutboxHandler].
     *
     * Stable IDs and aliases declared by the annotation are copied to each unvalidated candidate.
     *
     * @param bean Bean to inspect for annotated handler methods
     * @param beanName Spring name of the inspected bean
     * @return Unvalidated primary declarations in Spring's introspection order
     */
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
                    payloadType = method.parameterTypes.firstOrNull(),
                    source = HandlerSource.ANNOTATION,
                    configuredId = annotation.id.takeIf { it.isNotEmpty() },
                    configuredAliases = annotation.aliases.toSet(),
                    lambdaBeanNameId = null,
                    supportsPayload = { _, _ -> true },
                )
            }.toList()
}
