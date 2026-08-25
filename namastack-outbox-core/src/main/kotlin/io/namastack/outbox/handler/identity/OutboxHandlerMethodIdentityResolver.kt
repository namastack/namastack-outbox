package io.namastack.outbox.handler.identity

import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import org.springframework.aop.support.AopUtils
import org.springframework.util.ClassUtils

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
    /** Resolves and validates the canonical ID and aliases for [candidate]. */
    fun resolve(candidate: HandlerCandidate): HandlerIdentity {
        check(candidate.configuredId == null || candidate.configuredId.isNotBlank()) {
            "Blank handler ID configured for ${candidate.method.toGenericString()}"
        }
        check(candidate.configuredAliases.none { it.isBlank() }) {
            "Blank handler alias configured for ${candidate.method.toGenericString()}"
        }

        val legacyGeneratedId = OutboxHandlerMethod.generatedId(candidate.bean, candidate.method)
        val legacyRuntimeId =
            OutboxHandlerMethod.generatedId(
                candidate.bean,
                candidate.method,
                candidate.bean::class.java,
            )
        val canonicalId = candidate.configuredId ?: candidate.lambdaBeanNameId ?: legacyGeneratedId
        val aliases =
            buildSet {
                addAll(candidate.configuredAliases)
                if (candidate.configuredId != null && candidate.lambdaBeanNameId == null) add(legacyGeneratedId)
                if (
                    AopUtils.isAopProxy(candidate.bean) &&
                    !ClassUtils.isLambdaClass(AopUtils.getTargetClass(candidate.bean)) &&
                    canonicalId != legacyRuntimeId
                ) {
                    add(legacyRuntimeId)
                }
                remove(canonicalId)
            }

        return HandlerIdentity(canonicalId, aliases)
    }
}
