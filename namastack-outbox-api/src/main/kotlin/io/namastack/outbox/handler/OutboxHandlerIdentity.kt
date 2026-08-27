package io.namastack.outbox.handler

/**
 * Configures the stable routing identity of an interface-based outbox handler.
 *
 * When present, [id] is written to newly scheduled records. A `null` ID retains the generated
 * handler identity while still allowing [aliases] to be configured. Aliases are lookup-only
 * identities used to route records persisted under an earlier handler identity.
 *
 * @property id Optional stable canonical handler ID; `null` retains the generated identity
 * @property aliases Alternative lookup-only IDs for persisted records
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
data class OutboxHandlerIdentity
    @JvmOverloads
    constructor(
        val id: String? = null,
        val aliases: Set<String> = emptySet(),
    )
