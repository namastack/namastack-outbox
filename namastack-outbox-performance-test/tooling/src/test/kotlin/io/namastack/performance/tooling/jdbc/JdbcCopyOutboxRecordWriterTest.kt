package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.performanceRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.Connection
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.sql.SQLException
import java.time.Instant

/** Verifies that the optional load transport preserves the normal JDBC producer's records. */
@Testcontainers
class JdbcCopyOutboxRecordWriterTest {
    private fun connect() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    @BeforeEach
    fun prepare() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS outbox_record (
                        id text PRIMARY KEY, status text NOT NULL, record_key text NOT NULL,
                        record_type text NOT NULL, payload text NOT NULL, context text NOT NULL,
                        created_at timestamptz NOT NULL, completed_at timestamptz,
                        failure_count integer NOT NULL, failure_reason text, next_retry_at timestamptz NOT NULL,
                        partition_no integer NOT NULL, handler_id text NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("TRUNCATE outbox_record")
            }
        }
    }

    @Test
    fun `binary COPY and JDBC preserve identical columns including timestamp rounding`() {
        val instants = listOf(
            Instant.parse("2026-11-01T12:34:56.123456789Z"),
            Instant.parse("2026-11-01T12:34:56.123456500Z"),
            Instant.parse("2026-11-01T12:34:56.123457500Z"),
            Instant.parse("2026-11-01T12:34:56.999999999Z"),
            Instant.parse("1999-12-31T23:59:59.999999500Z"),
            Instant.parse("1969-12-31T23:59:59.123456499Z"),
        )
        val records = instants.mapIndexed { index, instant ->
            performanceRecord("transport", index.toLong(), 10, instant).copy(
                status = if (index % 2 == 0) "NEW" else "COMPLETED",
                recordKey = "κλειδί 中文 😀 '$index\t\n\\",
                payload = "{\"quote\":\"\\\"\",\"text\":\"ä😀\"}",
                context = "{\"tab\":\"\\t\"}",
                completedAt = if (index % 2 == 0) null else instant,
                failureCount = index,
                failureReason = if (index % 2 == 0) null else "échec\n'$index\\",
            )
        }
        JdbcOutboxRecordWriter(connect()).use { it.append(records) }
        val jdbc = rows()
        connect().use { it.createStatement().use { statement -> statement.execute("TRUNCATE outbox_record") } }
        connect().use { connection ->
            connection.createStatement().use { it.execute("SET TIME ZONE 'Pacific/Auckland'") }
            JdbcCopyOutboxRecordWriter(connection).use { it.append(records) }
        }
        assertEquals(jdbc, rows())
    }

    @Test
    fun `a duplicate inside COPY rolls back every record of that append`() {
        val record = performanceRecord("duplicate", 0, 10, Instant.now())
        JdbcCopyOutboxRecordWriter(connect()).use { writer ->
            assertThrows(SQLException::class.java) { writer.append(listOf(record, record)) }
        }
        assertEquals(emptyList<List<Any?>>(), rows())
    }

    @Test
    fun `append returns only after rows are visible on an independent connection`() {
        val record = performanceRecord("visibility", 0, 10, Instant.now())
        JdbcCopyOutboxRecordWriter(connect()).use { it.append(listOf(record)) }
        assertEquals(1, rows().size)
    }

    @Test
    fun `COPY is not acknowledged or visible while commit is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val record = performanceRecord("blocked", 0, 10, Instant.now())
        connect().use { connection ->
            val delayed = commitProxy(connection) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                connection.commit()
            }
            Executors.newSingleThreadExecutor().use { executor ->
                JdbcCopyOutboxRecordWriter(delayed).use { writer ->
                    val result = executor.submit { writer.append(listOf(record)) }
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS))
                        assertFalse(result.isDone)
                        assertEquals(0, rows().size)
                    } finally {
                        release.countDown()
                    }
                    result.get(10, TimeUnit.SECONDS)
                    assertEquals(1, rows().size)
                }
            }
        }
    }

    @Test
    fun `lost commit acknowledgement propagates without repeating COPY`() {
        val record = performanceRecord("uncertain", 0, 10, Instant.now())
        connect().use { connection ->
            val uncertain = commitProxy(connection) {
                connection.commit()
                throw SQLException("Simulated lost commit acknowledgement")
            }
            JdbcCopyOutboxRecordWriter(uncertain).use { writer ->
                assertThrows(SQLException::class.java) { writer.append(listOf(record)) }
                assertEquals(1, rows().size)
            }
        }
    }

    private fun commitProxy(connection: Connection, commit: () -> Unit): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit") {
                commit()
                null
            } else {
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        } as Connection

    private fun rows(): List<List<Any?>> =
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM outbox_record ORDER BY id").use { results ->
                    buildList {
                        while (results.next()) add((1..results.metaData.columnCount).map(results::getObject))
                    }
                }
            }
        }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
