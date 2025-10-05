package io.namastack.springoutbox

fun interface OutboxRecordProcessor {
    fun process(record: OutboxRecord)
}
