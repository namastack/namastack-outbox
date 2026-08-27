package io.namastack.outbox.handler.identity

import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod

/**
 * Resolves stable handler identity while retaining aliases for persisted records.
 *
 * Explicit IDs and lambda bean-name IDs take precedence over generated method IDs. Generated
 * legacy and proxy IDs are retained as aliases when required for backward-compatible routing.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object OutboxHandlerMethodIdentityResolver {
    /**
     * Resolves and validates the routing identity of a primary handler declaration.
     *
     * Explicit IDs take precedence over lambda bean names and generated method IDs. Generated
     * identities that may already exist in persisted records are retained as lookup aliases.
     *
     * @param candidate Handler declaration whose identity is resolved
     * @return Validated canonical ID and lookup aliases
     * @throws IllegalStateException if a configured ID or alias is blank
     */
    fun resolve(candidate: HandlerCandidate): HandlerIdentity {
        check(candidate.configuredId == null || candidate.configuredId.isNotBlank()) {
            "Blank handler ID configured for ${candidate.method.toGenericString()}"
        }
        check(candidate.configuredAliases.none { it.isBlank() }) {
            "Blank handler alias configured for ${candidate.method.toGenericString()}"
        }

        val generatedId = OutboxHandlerMethod.generatedId(candidate.bean, candidate.method)
        val runtimeGeneratedId =
            OutboxHandlerMethod.generatedId(
                candidate.bean,
                candidate.method,
                candidate.bean::class.java,
            )
        val canonicalId = candidate.configuredId ?: candidate.lambdaBeanNameId ?: generatedId
        val aliases =
            buildSet {
                val isLambda = candidate.lambdaBeanNameId != null

                addAll(candidate.configuredAliases)
                if (!isLambda && generatedId != canonicalId) add(generatedId)
                if (!isLambda && runtimeGeneratedId != canonicalId && runtimeGeneratedId != generatedId) {
                    add(runtimeGeneratedId)
                }
                remove(canonicalId)
            }

        return HandlerIdentity(canonicalId, aliases)
    }
}
