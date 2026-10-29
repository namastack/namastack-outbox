package io.namastack.performance.tooling.collector

import io.namastack.performance.tooling.report.BacklogSample
import io.namastack.performance.tooling.run.StatusCounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SampleRatesTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `partial endpoint has its actual duration rather than equal weight`() {
        val samples = listOf(sample(0, 0), sample(15000, 600000), sample(15100, 602000))
        assertEquals(602000.0 / 15.1, averageSampleRate(samples.reversed()) { it.counts.completedRecords }, 0.00001)
    }

    @Test
    fun `no elapsed interval has zero rate`() {
        assertEquals(0.0, averageSampleRate(emptyList()) { it.counts.completedRecords })
        assertEquals(0.0, averageSampleRate(listOf(sample(0, 1), sample(0, 2))) { it.counts.completedRecords })
    }

    private fun sample(
        milliseconds: Long,
        completed: Long,
    ) = BacklogSample(start.plusMillis(milliseconds), "PRODUCTION", StatusCounts(0, completed, 0, 0, null))
}
