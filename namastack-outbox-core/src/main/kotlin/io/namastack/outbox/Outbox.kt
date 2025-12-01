package io.namastack.outbox

interface Outbox {
    fun schedule(outboxRecord: OutboxRecord)
}
