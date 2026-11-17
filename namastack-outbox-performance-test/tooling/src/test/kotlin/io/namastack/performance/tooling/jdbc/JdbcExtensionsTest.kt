package io.namastack.performance.tooling.jdbc

import io.mockk.mockk
import io.mockk.verify
import io.namastack.performance.tooling.record.performanceRecord
import org.junit.jupiter.api.Test
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant

class JdbcExtensionsTest {
    @Test
    fun `binding preserves completion and failure metadata in benchmark fixtures`() {
        val completedAt = Instant.parse("2026-01-01T00:00:00Z")
        val record =
            performanceRecord("fixture", 0, 10, completedAt).copy(
                status = "COMPLETED",
                completedAt = completedAt,
                failureReason = "previous failure",
            )
        val statement = mockk<PreparedStatement>(relaxed = true)

        statement.bind(record)

        verify { statement.setTimestamp(8, Timestamp.from(completedAt)) }
        verify { statement.setString(10, "previous failure") }
        verify { statement.addBatch() }
    }
}
