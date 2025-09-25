package com.beisel.springoutbox

fun interface OutboxRecordProcessor {
    fun process(record: OutboxRecord)
}
