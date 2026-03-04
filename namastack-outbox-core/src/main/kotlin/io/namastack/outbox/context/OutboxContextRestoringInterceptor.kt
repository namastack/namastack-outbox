package io.namastack.outbox.context

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxFallbackHandlerInterceptor
import io.namastack.outbox.handler.OutboxHandlerInterceptor
import io.namastack.outbox.handler.OutboxRecordMetadata

class OutboxContextRestoringInterceptor(
    private val restorers: List<OutboxContextRestorer>,
) : OutboxHandlerInterceptor,
    OutboxFallbackHandlerInterceptor {
    override fun intercept(
        payload: Any,
        metadata: OutboxRecordMetadata,
        next: () -> Unit,
    ) {
        intercept(metadata.context, next)
    }

    override fun intercept(
        payload: Any,
        context: OutboxFailureContext,
        next: () -> Unit,
    ) {
        intercept(context.context, next)
    }

    private fun intercept(
        context: Map<String, String>,
        next: () -> Unit,
    ) {
        if (restorers.isEmpty()) return next()

        restorers
            .asReversed()
            .fold(next) { acc, restorer ->
                {
                    restorer.withContext(context) {
                        acc()
                    }
                }
            }.invoke()
    }
}
