package io.namastack.outbox.instrumentation

/**
 * Identifies the handler role for one outbox processing attempt.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
enum class OutboxProcessHandlerKind {
    /** The primary outbox handler. */
    PRIMARY,

    /** The fallback handler invoked after primary processing fails. */
    FALLBACK,
}
