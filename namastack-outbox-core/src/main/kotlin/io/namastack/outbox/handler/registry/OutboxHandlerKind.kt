package io.namastack.outbox.handler.registry

/**
 * Public handler kind used for operational handler metadata.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
enum class OutboxHandlerKind {
    TYPED,
    GENERIC,
}
