package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.springframework.core.annotation.AnnotationUtils

/**
 * Selector that matches by annotation on payload class.
 *
 * Uses Spring's [AnnotationUtils.findAnnotation] which supports:
 * - Direct annotations on the class
 * - Meta-annotations (annotations on annotations)
 * - Inherited annotations from superclasses
 *
 * @param A The annotation type to match
 * @author Roland Beisel
 * @since 1.1.0
 */
internal class AnnotationSelector<A : Annotation>(
    private val annotationType: Class<A>,
) : OutboxPayloadSelector {
    override fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = AnnotationUtils.findAnnotation(payload::class.java, annotationType) != null
}
