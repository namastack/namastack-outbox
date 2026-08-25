package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata

/**
 * Validates declaration relationships and supported signatures before handler assembly.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object HandlerDiscoveryValidator {
    /** Rejects a method declared through both an annotation and a handler interface. */
    fun validateRelationships(declarations: HandlerDeclarations) {
        declarations.handlers
            .groupBy { it.method }
            .values
            .firstOrNull { candidates -> candidates.map { it.source }.distinct().size > 1 }
            ?.let { candidates ->
                throw IllegalStateException(
                    "Handler method ${candidates.first().method.toGenericString()} was discovered through both annotation and interface declarations",
                )
            }
    }

    /** Returns whether a primary declaration has a supported method signature. */
    fun supportsHandler(candidate: HandlerCandidate): Boolean {
        val parameters = candidate.method.parameterTypes
        return when (candidate.source) {
            HandlerSource.TYPED_INTERFACE,
            HandlerSource.GENERIC_INTERFACE,
            -> true

            HandlerSource.ANNOTATION ->
                (parameters.size == 1 && parameters[0] != Any::class.java) ||
                    (parameters.size == 2 && parameters[1] == OutboxRecordMetadata::class.java)
        }
    }

    /** Returns whether a fallback accepts a payload and an [OutboxFailureContext]. */
    fun supportsFallback(candidate: FallbackCandidate): Boolean =
        candidate.method.parameterCount == 2 &&
            candidate.method.parameterTypes[1] == OutboxFailureContext::class.java
}
