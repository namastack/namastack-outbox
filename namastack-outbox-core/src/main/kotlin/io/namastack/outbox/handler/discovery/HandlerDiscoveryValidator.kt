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
    /**
     * Validates relationships between primary declarations discovered on one bean.
     *
     * @param declarations Primary and fallback declarations to validate
     * @throws IllegalStateException if one method has an ambiguous interface contract or is
     * discovered through both an annotation and an interface
     */
    fun validateRelationships(declarations: HandlerDeclarations) {
        rejectAmbiguousInterfaceHandler(declarations.handlers)
        rejectMixedAnnotationAndInterface(declarations.handlers)
    }

    /**
     * Determines whether a primary declaration has a supported method signature.
     *
     * Interface declarations are guaranteed by their contracts. Annotated declarations must
     * accept either one typed payload parameter or a payload followed by [OutboxRecordMetadata].
     *
     * @param candidate Primary handler declaration to inspect
     * @return `true` if the declaration can be assembled into an invocable handler
     */
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

    /**
     * Determines whether a fallback has the required payload-and-context signature.
     *
     * @param candidate Fallback declaration to inspect
     * @return `true` if the second parameter is an [OutboxFailureContext] and exactly two
     * parameters are declared
     */
    fun supportsFallback(candidate: FallbackCandidate): Boolean =
        candidate.method.parameterCount == 2 &&
            candidate.method.parameterTypes[1] == OutboxFailureContext::class.java

    private fun rejectAmbiguousInterfaceHandler(candidates: List<HandlerCandidate>) {
        candidates
            .groupBy { it.method }
            .values
            .firstOrNull { group ->
                val sources = group.map { it.source }.toSet()
                HandlerSource.TYPED_INTERFACE in sources && HandlerSource.GENERIC_INTERFACE in sources
            }?.let { group ->
                throw IllegalStateException(
                    "Handler method ${group.first().method.toGenericString()} implements both OutboxHandler and " +
                        "OutboxTypedHandler<Any>, resulting in an ambiguous handle(Any, OutboxRecordMetadata) signature",
                )
            }
    }

    private fun rejectMixedAnnotationAndInterface(candidates: List<HandlerCandidate>) {
        candidates
            .groupBy { it.method }
            .values
            .firstOrNull { group ->
                val sources = group.map { it.source }.toSet()
                HandlerSource.ANNOTATION in sources && sources.size > 1
            }?.let { group ->
                throw IllegalStateException(
                    "Handler method ${group.first().method.toGenericString()} was discovered through both annotation and interface declarations",
                )
            }
    }
}
