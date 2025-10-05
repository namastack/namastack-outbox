package io.namastack.springoutbox

enum class OutboxRecordStatus {
    NEW,
    COMPLETED,
    FAILED,
}
