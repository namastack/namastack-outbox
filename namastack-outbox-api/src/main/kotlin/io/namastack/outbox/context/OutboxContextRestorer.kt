package io.namastack.outbox.context

interface OutboxContextRestorer {
    /**
     * Restore context for the duration of [block] and guarantee cleanup.
     */
    fun withContext(
        context: Map<String, String>,
        block: () -> Unit,
    )
}
