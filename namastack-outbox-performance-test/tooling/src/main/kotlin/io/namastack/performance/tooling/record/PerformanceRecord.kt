package io.namastack.performance.tooling.record

import io.namastack.outbox.partition.PartitionHasher
import java.time.Instant

internal const val RECORD_TYPE = "io.namastack.performance.consumer.PaymentRequestedEvent"
internal const val HANDLER_ID =
    "io.namastack.performance.consumer.PaymentRequestedEventHandler" +
        "#handle(io.namastack.performance.consumer.PaymentRequestedEvent," +
        "io.namastack.outbox.handler.OutboxRecordMetadata)"

internal data class PerformanceRecord(
    val id: String,
    val status: String,
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val context: String,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failureCount: Int,
    val failureReason: String?,
    val nextRetryAt: Instant,
    val partition: Int,
    val handlerId: String,
) {
    fun values(): List<String?> =
        listOf(
            id,
            status,
            recordKey,
            recordType,
            payload,
            context,
            createdAt.toString(),
            completedAt?.toString(),
            failureCount.toString(),
            failureReason,
            nextRetryAt.toString(),
            partition.toString(),
            handlerId,
        )
}

internal fun performanceRecord(
    runId: String,
    index: Long,
    recordsPerKey: Int,
    createdAt: Instant,
): PerformanceRecord {
    val sequence = index + 1
    val recordKey = "payment:${(index / recordsPerKey).toString().padStart(9, '0')}"
    return PerformanceRecord(
        id = "$runId-record-${sequence.toString().padStart(12, '0')}",
        status = "NEW",
        recordKey = recordKey,
        recordType = RECORD_TYPE,
        payload =
            """{"paymentId":"pay-${sequence.toString().padStart(12, '0')}","orderId":"order-$sequence","customerId":"customer-$sequence","amount":129.90,"currency":"EUR"}""",
        context = """{"benchmarkRunId":"$runId"}""",
        createdAt = createdAt,
        completedAt = null,
        failureCount = 0,
        failureReason = null,
        nextRetryAt = createdAt,
        partition = PartitionHasher.getPartitionForRecordKey(recordKey),
        handlerId = HANDLER_ID,
    )
}
