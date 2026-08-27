package io.namastack.outbox.handler.invoker

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.retry.OutboxRetryPolicy

/**
 * Registration projection needed to invoke a fallback with the correct retry policy.
 *
 * @property fallback fallback method associated with the failed primary handler
 * @property explicitRetryPolicy handler-specific policy, or `null` to use the registry default
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal data class FallbackInvocationTarget(
    val fallback: OutboxFallbackHandlerMethod,
    val explicitRetryPolicy: OutboxRetryPolicy?,
)
