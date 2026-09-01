package io.namastack.outbox

/**
 * Resolved scheduling values for one Spring event carrying an outbox payload.
 *
 * @property payload Domain event payload to schedule
 * @property key Resolved record key or a generated UUID when no key expression is configured
 * @property context Resolved event-specific context entries
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class ResolvedOutboxEvent(
    val payload: Any,
    val key: String,
    val context: Map<String, String>,
)
