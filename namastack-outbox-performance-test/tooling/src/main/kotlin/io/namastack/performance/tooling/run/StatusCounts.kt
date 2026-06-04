package io.namastack.performance.tooling.run

import java.time.Instant

internal data class StatusCounts(
    val newRecords: Long,
    val completedRecords: Long,
    val failedRecords: Long,
    val retryCount: Long,
    val lastCompletedAt: Instant?,
) {
    fun totalRecords() = newRecords + completedRecords + failedRecords

    fun isTerminal(expected: Long) = newRecords == 0L && completedRecords + failedRecords >= expected

    fun isValid(expected: Long) = newRecords == 0L && completedRecords == expected && failedRecords == 0L && retryCount == 0L

    fun properties() = "newRecords=$newRecords\ncompletedRecords=$completedRecords\nfailedRecords=$failedRecords\nretryCount=$retryCount"
}
