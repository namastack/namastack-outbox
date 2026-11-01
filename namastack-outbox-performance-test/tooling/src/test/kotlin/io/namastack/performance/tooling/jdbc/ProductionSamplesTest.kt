package io.namastack.performance.tooling.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ProductionSamplesTest {
    @Test
    fun `reconstruction keeps backlog across buckets and preserves the exact partial endpoint`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val samples =
            reconstructProductionSamples(
                start,
                start.plusSeconds(31),
                Duration.ofSeconds(15),
                mapOf(1L to (100L to 50L), 2L to (100L to 100L), 3L to (10L to 40L)),
            )
        assertEquals(listOf(0L, 50L, 50L, 20L), samples.map { it.counts.newRecords })
        assertEquals(listOf(0L, 50L, 150L, 190L), samples.map { it.counts.completedRecords })
        assertEquals(start.plusSeconds(31), samples.last().timestamp)
        assertEquals("PRODUCTION_END", samples.last().phase)
    }

    @Test
    fun `completion before production fails rather than overstating capacity`() {
        val now = Instant.now()
        assertThrows(IllegalStateException::class.java) {
            reconstructProductionSamples(now, now.plusSeconds(15), Duration.ofSeconds(15), mapOf(0L to (0L to 1L)))
        }
    }
}
