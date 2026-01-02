package io.namastack.outbox.handler.scanner

import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod

/**
 * Result of scanning a bean for a handler and its optional fallback.
 *
 * Encapsulates the handler method and its associated fallback (if present).
 * This ensures that handlers and fallbacks are always correctly paired during registration.
 *
 * @property handler The discovered handler method
 * @property fallback Optional fallback handler (null if handler has no fallback)
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
data class HandlerScanResult(
    val handler: OutboxHandlerMethod,
    val fallback: OutboxFallbackHandlerMethod? = null,
)
