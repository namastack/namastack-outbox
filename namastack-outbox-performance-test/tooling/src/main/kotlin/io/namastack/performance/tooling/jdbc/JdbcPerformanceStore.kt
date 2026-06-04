package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.run.ClusterStatus
import io.namastack.performance.tooling.report.EnvironmentInfo
import io.namastack.performance.tooling.report.LatencyStats
import io.namastack.performance.tooling.store.OutboxRecordWriter
import io.namastack.performance.tooling.report.PartitionStats
import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.store.PerformanceStore
import io.namastack.performance.tooling.run.RunDefinition
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts
import io.namastack.performance.tooling.run.TriggerResult
import io.namastack.performance.tooling.internal.commandOutput
import io.namastack.performance.tooling.internal.elapsedMillis
import io.namastack.performance.tooling.internal.escapeLike
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

internal class JdbcPerformanceStore(
    private val config: JdbcDatabaseConfig,
) : PerformanceStore {
    override fun saveRun(definition: RunDefinition) {
        connect().use { connection ->
            connection.prepareStatement(JdbcPerformanceSql.saveRun).use { statement ->
                statement.bind(definition)
                statement.executeUpdate()
            }
        }
    }

    override fun replaceStagedRecords(
        runId: String,
        records: Sequence<PerformanceRecord>,
    ): Long =
        connect().use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("DELETE FROM performance_seed_record WHERE run_id = ?").use {
                it.setString(1, runId)
                it.executeUpdate()
            }
            val copied =
                connection
                    .unwrap(PGConnection::class.java)
                    .copyAPI
                    .copyIn(JdbcPerformanceSql.copySeedRecords, JdbcSeedInputStream(runId, records.iterator()))
            connection.commit()
            copied
        }

    override fun markSeeded(runId: String) {
        connect().use { connection ->
            connection.prepareStatement("UPDATE performance_test_run SET status = 'SEEDED', seeded_at = clock_timestamp() WHERE run_id = ?").use {
                it.setString(1, runId)
                check(it.executeUpdate() == 1) { "Unknown run id '$runId'" }
            }
        }
    }

    override fun trigger(
        runId: String,
        timeout: Duration,
    ): TriggerResult =
        connect().use { connection ->
            connection.autoCommit = false
            val startedAt = System.nanoTime()
            connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '${timeout.toMillis()}ms'") }
            connection.prepareStatement("UPDATE performance_test_run SET status = 'TRIGGERING', trigger_started_at = clock_timestamp() WHERE run_id = ?").use {
                it.setString(1, runId)
                check(it.executeUpdate() == 1) { "Unknown run id '$runId'" }
            }
            val inserted =
                connection.prepareStatement(JdbcPerformanceSql.trigger).use {
                    it.setString(1, runId)
                    it.executeUpdate()
                }
            val durationMs = elapsedMillis(startedAt)
            val runStartedAt =
                connection.prepareStatement(
                    """
                    UPDATE performance_test_run
                    SET status = 'RUNNING', started_at = clock_timestamp(), trigger_duration_ms = ?
                    WHERE run_id = ? RETURNING started_at
                    """.trimIndent(),
                ).use {
                    it.setLong(1, durationMs)
                    it.setString(2, runId)
                    it.executeQuery().use { result ->
                        check(result.next())
                        result.getTimestamp(1).toInstant()
                    }
                }
            connection.commit()
            TriggerResult(inserted, runStartedAt, durationMs)
        }

    override fun beginProduction(definition: RunDefinition): Instant =
        connect().use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(JdbcPerformanceSql.saveRun).use { statement ->
                statement.bind(definition)
                statement.executeUpdate()
            }
            val startedAt =
                connection.prepareStatement(
                    """
                    UPDATE performance_test_run
                    SET status = 'RUNNING', started_at = clock_timestamp(), producer_started_at = clock_timestamp()
                    WHERE run_id = ? RETURNING producer_started_at
                    """.trimIndent(),
                ).use {
                    it.setString(1, definition.runId)
                    it.executeQuery().use { result ->
                        check(result.next())
                        result.getTimestamp(1).toInstant()
                    }
                }
            connection.commit()
            startedAt
        }

    override fun openRecordWriter(): OutboxRecordWriter = JdbcOutboxRecordWriter(connect())

    override fun markProduced(
        runId: String,
        producedRecords: Long,
    ): Instant =
        connect().use { connection ->
            connection.prepareStatement(
                """
                UPDATE performance_test_run
                SET status = 'PRODUCED', producer_completed_at = clock_timestamp(), produced_records = ?
                WHERE run_id = ? RETURNING producer_completed_at
                """.trimIndent(),
            ).use {
                it.setLong(1, producedRecords)
                it.setString(2, runId)
                it.executeQuery().use { result ->
                    check(result.next())
                    result.getTimestamp(1).toInstant()
                }
            }
        }

    override fun findRun(runId: String): RunInfo? =
        connect().use { connection ->
            connection.prepareStatement("SELECT * FROM performance_test_run WHERE run_id = ?").use {
                it.setString(1, runId)
                it.executeQuery().use { result ->
                    if (!result.next()) null else result.toRunInfo()
                }
            }
        }

    override fun statusCounts(): StatusCounts =
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*) FILTER (WHERE status = 'NEW'),
                           COUNT(*) FILTER (WHERE status = 'COMPLETED'),
                           COUNT(*) FILTER (WHERE status = 'FAILED'),
                           COALESCE(SUM(failure_count), 0),
                           MAX(completed_at)
                    FROM outbox_record
                    """.trimIndent(),
                ).use {
                    check(it.next())
                    StatusCounts(it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getTimestamp(5)?.toInstant())
                }
            }
        }

    override fun statusCountsAt(timestamp: Instant): StatusCounts =
        connect().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*),
                       COUNT(*) FILTER (WHERE completed_at <= ?),
                       COUNT(*) FILTER (WHERE status = 'FAILED'),
                       COALESCE(SUM(failure_count), 0),
                       MAX(completed_at) FILTER (WHERE completed_at <= ?)
                FROM outbox_record WHERE created_at <= ?
                """.trimIndent(),
            ).use {
                val value = Timestamp.from(timestamp)
                it.setTimestamp(1, value)
                it.setTimestamp(2, value)
                it.setTimestamp(3, value)
                it.executeQuery().use { result ->
                    check(result.next())
                    val total = result.getLong(1)
                    val completed = result.getLong(2)
                    val failed = result.getLong(3)
                    StatusCounts(total - completed - failed, completed, failed, result.getLong(4), result.getTimestamp(5)?.toInstant())
                }
            }
        }

    override fun clusterStatus(): ClusterStatus =
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT
                      (SELECT COUNT(*) FROM outbox_instance WHERE status = 'ACTIVE'),
                      (SELECT COUNT(*) FROM outbox_partition WHERE instance_id IS NOT NULL)
                    """.trimIndent(),
                ).use {
                    check(it.next())
                    ClusterStatus(it.getLong(1), it.getLong(2))
                }
            }
        }

    override fun resetOutbox() {
        connect().use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE TABLE outbox_record")
                it.execute("TRUNCATE TABLE performance_seed_record")
            }
        }
    }

    override fun completeRun(
        runId: String,
        counts: StatusCounts,
    ) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                UPDATE performance_test_run
                SET status = ?, completed_at = clock_timestamp(), last_completed_at = ?,
                    completed_records = ?, failed_records = ?, retry_count = ?
                WHERE run_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, if (counts.failedRecords == 0L && counts.retryCount == 0L) "VALID" else "INVALID")
                it.setTimestamp(2, counts.lastCompletedAt?.let(Timestamp::from))
                it.setLong(3, counts.completedRecords)
                it.setLong(4, counts.failedRecords)
                it.setLong(5, counts.retryCount)
                it.setString(6, runId)
                it.executeUpdate()
            }
        }
    }

    override fun latencyStats(runId: String): LatencyStats =
        connect().use { connection ->
            connection.prepareStatement(
                """
                SELECT COALESCE(AVG(EXTRACT(EPOCH FROM completed_at - created_at)), 0),
                       COALESCE(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM completed_at - created_at)), 0),
                       COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM completed_at - created_at)), 0),
                       COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM completed_at - created_at)), 0),
                       COALESCE(MAX(EXTRACT(EPOCH FROM completed_at - created_at)), 0)
                FROM outbox_record WHERE id LIKE ? ESCAPE '\' AND completed_at IS NOT NULL
                """.trimIndent(),
            ).use {
                it.setString(1, "${escapeLike(runId)}-record-%")
                it.executeQuery().use { result ->
                    check(result.next())
                    LatencyStats(result.getDouble(1), result.getDouble(2), result.getDouble(3), result.getDouble(4), result.getDouble(5))
                }
            }
        }

    override fun partitionStats(): PartitionStats =
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*), COALESCE(MIN(records), 0), COALESCE(AVG(records), 0), COALESCE(MAX(records), 0)
                    FROM (SELECT partition_no, COUNT(*) AS records FROM outbox_record GROUP BY partition_no) partitions
                    """.trimIndent(),
                ).use {
                    check(it.next())
                    PartitionStats(it.getLong(1), it.getLong(2), it.getDouble(3), it.getLong(4))
                }
            }
        }

    override fun environmentInfo(): EnvironmentInfo {
        val postgresVersion =
            connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SHOW server_version").use {
                        check(it.next())
                        it.getString(1)
                    }
                }
            }
        return EnvironmentInfo(
            gitCommit = commandOutput("git", "-C", "..", "rev-parse", "HEAD") ?: "unknown",
            gitDirty = commandOutput("git", "-C", "..", "status", "--porcelain")?.isNotEmpty() ?: false,
            javaVersion = System.getProperty("java.version"),
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            dockerVersion = commandOutput("docker", "version", "--format", "{{.Server.Version}}") ?: "unknown",
            postgresVersion = postgresVersion,
        )
    }

    private fun connect(): Connection = DriverManager.getConnection(config.url, config.user, config.password)

}
