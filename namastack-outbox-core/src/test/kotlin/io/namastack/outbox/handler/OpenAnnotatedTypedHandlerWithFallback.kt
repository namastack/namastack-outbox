package io.namastack.outbox.handler

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler

/** Proxyable annotated fixture used to verify stable fallback aliases. */
@Suppress("UNUSED_PARAMETER")
open class OpenAnnotatedTypedHandlerWithFallback {
    @OutboxHandler
    open fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    open fun handleFailure(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }
}
