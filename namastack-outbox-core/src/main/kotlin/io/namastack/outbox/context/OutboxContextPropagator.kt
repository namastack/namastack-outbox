package io.namastack.outbox.context

import io.namastack.outbox.OutboxRecord
import java.io.Closeable

interface OutboxContextPropagator {
    companion object {
        val NoopScope: Scope = object : Scope {}
    }

    fun openScope(record: OutboxRecord<*>): Scope

    interface Scope : Closeable {
        fun onSuccess() {
            return
        }

        fun onError(error: Exception) {
            return
        }

        override fun close() {
            return
        }
    }
}
