package io.namastack.outbox.handler.scanner

import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.retry.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.core.annotation.AnnotatedElementUtils

/**
 * Scanner for resolving retry policies for handler methods.
 *
 * Implements a cascading resolution strategy to determine which retry policy
 * should be used for a specific handler method. This allows fine-grained control
 * over retry behavior per handler.
 *
 * ## Resolution Priority
 *
 * 1. **@OutboxRetryable annotation** - Highest priority
 *    - By class: `@OutboxRetryable(AggressiveRetryPolicy::class)`
 *    - By name: `@OutboxRetryable(name = "aggressiveRetryPolicy")`
 *
 * 2. **OutboxRetryAware interface** - Medium priority
 *    - Handler implements interface and returns policy programmatically
 *
 * 3. **Default policy** - Lowest priority (handled by registry fallback)
 *    - Configured via `outbox.retry.policy` property
 *
 * @param registry The retry policy registry for bean lookup and default policy access
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class RetryPolicyScanner(
    private val registry: OutboxRetryPolicyRegistry,
) {
    /**
     * Scans a handler method to resolve its retry policy.
     *
     * Uses a cascading strategy to determine the appropriate retry policy:
     *
     * 1. Checks for @OutboxRetryable annotation on the method
     *    - If annotation specifies a class (value != OutboxRetryPolicy::class),
     *      looks up Spring bean by class type
     *    - If annotation specifies a name (name.isNotBlank()),
     *      looks up Spring bean by bean name
     *
     * 2. Checks if the handler bean implements OutboxRetryAware interface
     *    - If yes, uses the policy returned by getRetryPolicy()
     *
     * 3. Returns null to signal: use default policy from registry
     *
     * ## Examples
     *
     * ### Annotation with class:
     * ```kotlin
     * @OutboxHandler
     * @OutboxRetryable(AggressiveRetryPolicy::class)
     * fun handlePayment(event: PaymentEvent) { ... }
     * ```
     *
     * ### Annotation with name:
     * ```kotlin
     * @OutboxHandler
     * @OutboxRetryable(name = "customRetryPolicy")
     * fun handleOrder(event: OrderEvent) { ... }
     * ```
     *
     * ### Interface implementation:
     * ```kotlin
     * class OrderHandler(
     *     private val customPolicy: OutboxRetryPolicy
     * ) : OutboxTypedHandler<OrderEvent>, OutboxRetryAware {
     *     override fun handle(payload: OrderEvent) { ... }
     *     override fun getRetryPolicy() = customPolicy
     * }
     * ```
     *
     * @param handlerMethod The handler method to scan for retry policy configuration
     * @return The resolved retry policy, or null if default should be used
     */
    fun scan(handlerMethod: OutboxHandlerMethod): OutboxRetryPolicy? {
        val bean = handlerMethod.bean

        // Priority 1: Check for @OutboxRetryable annotation on method
        AnnotatedElementUtils
            .findMergedAnnotation(
                handlerMethod.method,
                OutboxRetryable::class.java,
            )?.let { annotation ->
                // Option A: Retry policy specified by class (type-safe)
                if (annotation.value != OutboxRetryPolicy::class) {
                    return registry.getRetryPolicy(annotation.value)
                }
                // Option B: Retry policy specified by Spring bean name
                if (annotation.name.isNotBlank()) {
                    return registry.getRetryPolicy(annotation.name)
                }
            }

        // Priority 2: Check if bean implements OutboxRetryAware interface
        if (bean is OutboxRetryAware) {
            return bean.getRetryPolicy()
        }

        // Priority 3: Return null to signal default policy should be used
        // The registry will provide the default policy configured via outbox.retry.policy
        return null
    }
}
