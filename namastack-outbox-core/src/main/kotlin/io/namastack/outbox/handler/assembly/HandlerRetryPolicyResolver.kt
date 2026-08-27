package io.namastack.outbox.handler.assembly

import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.retry.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.core.annotation.AnnotatedElementUtils

/**
 * Resolves an explicitly configured retry policy without eagerly selecting the default policy.
 *
 * Method-level [OutboxRetryable] configuration takes precedence over an [OutboxRetryAware]
 * implementation. A `null` result deliberately leaves default-policy selection to invocation time.
 *
 * @param registry registry containing named and typed retry policies
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal class HandlerRetryPolicyResolver(
    private val registry: OutboxRetryPolicyRegistry,
) {
    /**
     * Resolves an explicitly configured retry policy for a handler declaration.
     *
     * Method-level [OutboxRetryable] configuration takes precedence over a policy supplied through
     * [OutboxRetryAware]. The default registry policy is intentionally not resolved here.
     *
     * @param candidate Handler declaration whose retry configuration is inspected
     * @return The explicitly configured policy, or `null` to resolve the default policy at invocation time
     */
    fun resolve(candidate: HandlerCandidate): OutboxRetryPolicy? {
        AnnotatedElementUtils.findMergedAnnotation(candidate.method, OutboxRetryable::class.java)?.let { annotation ->
            if (annotation.value != OutboxRetryPolicy::class) return registry.getRetryPolicy(annotation.value)
            if (annotation.name.isNotBlank()) return registry.getRetryPolicy(annotation.name)
        }

        return (candidate.bean as? OutboxRetryAware)?.getRetryPolicy()
    }
}
