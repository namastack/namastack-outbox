package io.namastack.outbox.handler.identity

/**
 * Stable routing identity assigned to a primary handler.
 *
 * @property canonicalId ID stored on newly scheduled records
 * @property aliases compatibility IDs that resolve persisted records to the same handler
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal data class HandlerIdentity(
    val canonicalId: String,
    val aliases: Set<String>,
)
