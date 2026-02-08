package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata

/**
 * Selector that matches by context value in metadata.
 *
 * Matches when the metadata context contains the specified key
 * with the expected value.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal class ContextValueSelector(
    private val contextKey: String,
    private val expectedValue: String,
) : OutboxPayloadSelector {
    override fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = metadata.context[contextKey] == expectedValue
}
