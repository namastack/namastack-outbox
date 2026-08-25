package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.retry.OutboxRetryPolicy

/**
 * Complete registration assembled for one primary handler.
 *
 * @property beanName Spring bean name used in routing-collision diagnostics
 * @property primary primary method and its canonical routing identity
 * @property fallback optional fallback paired with [primary]
 * @property explicitRetryPolicy handler-specific policy, or `null` to use the registry default lazily
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal data class HandlerRegistration(
    val beanName: String,
    val primary: OutboxHandlerMethod,
    val fallback: OutboxFallbackHandlerMethod?,
    val explicitRetryPolicy: OutboxRetryPolicy?,
)
