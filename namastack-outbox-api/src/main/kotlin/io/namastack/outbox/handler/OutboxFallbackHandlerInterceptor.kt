package io.namastack.outbox.handler

interface OutboxFallbackHandlerInterceptor {
    fun intercept(
        payload: Any,
        context: OutboxFailureContext,
        next: () -> Unit,
    )
}
