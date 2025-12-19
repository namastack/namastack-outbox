package io.namastack.outbox.context

interface OutboxContextProvider {
    fun provide(): Map<String, String>
}
