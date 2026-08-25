package io.namastack.outbox.handler.discovery

/**
 * Mechanism through which a handler declaration was discovered.
 *
 * The source keeps primary handlers and fallbacks paired within the same declaration model.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal enum class HandlerSource {
    ANNOTATION,
    TYPED_INTERFACE,
    GENERIC_INTERFACE,
}
