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
    /** Resolves annotation-based policies before an [OutboxRetryAware] policy. */
    fun resolve(candidate: HandlerCandidate): OutboxRetryPolicy? {
        AnnotatedElementUtils.findMergedAnnotation(candidate.method, OutboxRetryable::class.java)?.let { annotation ->
            if (annotation.value != OutboxRetryPolicy::class) return registry.getRetryPolicy(annotation.value)
            if (annotation.name.isNotBlank()) return registry.getRetryPolicy(annotation.name)
        }

        return (candidate.bean as? OutboxRetryAware)?.getRetryPolicy()
    }
}
