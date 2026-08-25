package io.namastack.outbox.handler.discovery

/**
 * Primary and fallback declarations discovered on one bean.
 *
 * @property handlers unvalidated primary handler declarations
 * @property fallbacks unvalidated fallback handler declarations
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal data class HandlerDeclarations(
    val handlers: List<HandlerCandidate>,
    val fallbacks: List<FallbackCandidate>,
)
