package io.namastack.outbox.handler

import io.namastack.outbox.annotation.OutboxHandler

/** Proxyable annotated handler fixture used to verify stable target-class identity. */
@Suppress("UNUSED_PARAMETER")
open class OpenAnnotatedTypedHandler {
    @OutboxHandler
    open fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }
}
