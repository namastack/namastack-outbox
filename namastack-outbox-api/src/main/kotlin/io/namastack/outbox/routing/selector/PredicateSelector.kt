package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.util.function.BiPredicate

/**
 * Selector that matches by custom predicate.
 *
 * Useful for complex matching logic that cannot be expressed with
 * type, annotation, or context value selectors.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal class PredicateSelector(
    private val predicate: BiPredicate<Any, OutboxRecordMetadata>,
) : OutboxPayloadSelector {
    override fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = predicate.test(payload, metadata)
}
