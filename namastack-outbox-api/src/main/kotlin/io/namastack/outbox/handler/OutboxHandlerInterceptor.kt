package io.namastack.outbox.handler

interface OutboxHandlerInterceptor {
    fun intercept(
        payload: Any,
        metadata: OutboxRecordMetadata,
        next: () -> Unit,
    )
}
