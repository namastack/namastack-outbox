package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.springframework.util.ClassUtils

/**
 * Selector that matches by payload type (supports inheritance).
 *
 * Uses Spring's [ClassUtils] for proper type checking,
 * which handles proxy classes and CGLIB-enhanced classes correctly.
 *
 * @param T The payload type to match
 * @author Roland Beisel
 * @since 1.1.0
 */
internal class TypeSelector<T : Any>(
    private val type: Class<T>,
) : OutboxPayloadSelector {
    override fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = ClassUtils.isAssignableValue(type, payload)
}
