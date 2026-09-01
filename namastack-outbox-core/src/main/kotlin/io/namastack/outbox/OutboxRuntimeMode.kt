package io.namastack.outbox

/**
 * Supported outbox bootstrap modes.
 *
 * A mode selects which runtime implementation assembles the outbox. Availability is validated
 * separately through [OutboxRuntimeModeProvider].
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
enum class OutboxRuntimeMode {
    /** The standard OSS single-runtime outbox. */
    SINGLE,

    /** Multiple managed outbox runtimes supplied by an additional module. */
    CHANNELS,
}
