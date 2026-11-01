package io.namastack.outbox

import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Persists completion batches and publishes their results after commit.
 *
 * Uses JDBC statement batching while the driver provides exact update counts. A successfully
 * rolled-back batch can fall back to individual statements or isolated transactions. Failures
 * during commit are propagated without replay because their database outcome may be unknown.
 *
 * Instances may be shared by several writer threads. Each transaction uses its own connection.
 *
 * @param jdbc JDBC operations for the fixed outbox namespace
 * @param transactionTemplate Transaction settings and manager used for completion commits
 * @param tableName Fully resolved record table name
 * @param bulkUpdateFactory Lazily selects an optional dialect-specific multi-row update strategy
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionWriter(
    private val jdbc: JdbcOperations,
    transactionTemplate: TransactionTemplate,
    tableName: String,
    bulkUpdateFactory: () -> JdbcOutboxBulkCompletionUpdater? = { null },
) {
    companion object {
        private const val MAX_LOCK_ATTEMPTS = 3
    }

    private class InexactUpdateCountsException : RuntimeException()

    /** Observing this outside TransactionTemplate.execute means rollback completed successfully. */
    private class RolledBackBatchException(
        val databaseFailure: DataAccessException,
    ) : RuntimeException(databaseFailure)

    private val transactions =
        TransactionTemplate(checkNotNull(transactionTemplate.transactionManager), transactionTemplate).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private val updateQuery =
        """
        UPDATE $tableName SET status = ?, completed_at = ?, failure_count = ?,
            failure_reason = ?, next_retry_at = ? WHERE id = ?
        """.trimIndent()

    private val bulkUpdater by lazy(bulkUpdateFactory)

    @Volatile
    private var useJdbcBatchUpdates = true

    /** Writes the batch, isolating rejected updates only after a confirmed rollback. */
    fun write(completions: List<JdbcOutboxCompletion>) {
        // Stable input order improves locality; a bulk SQL plan may acquire locks in a different order.
        val ordered = completions.sortedBy { it.id }
        try {
            val counts = writeWithExactCounts(ordered)
            ordered.forEachIndexed { index, completion -> completion.confirmCommit(counts[index] == 1) }
        } catch (_: RolledBackBatchException) {
            ordered.forEach { writeIsolated(it) }
        }
    }

    private fun writeWithExactCounts(completions: List<JdbcOutboxCompletion>): IntArray =
        try {
            writeTransaction(completions, useJdbcBatchUpdates)
        } catch (_: InexactUpdateCountsException) {
            // The first transaction rolled back. Use exact statements in a fresh shared transaction.
            useJdbcBatchUpdates = false
            writeTransaction(completions, useBatch = false)
        }

    private fun writeIsolated(completion: JdbcOutboxCompletion) {
        try {
            completion.confirmCommit(writeWithLockRetries(completion))
        } catch (failure: Throwable) {
            val cause = if (failure is RolledBackBatchException) failure.databaseFailure else failure
            completion.fail(cause)
            if (failure is Error) throw failure
        }
    }

    private fun writeWithLockRetries(completion: JdbcOutboxCompletion): Boolean {
        repeat(MAX_LOCK_ATTEMPTS) { attempt ->
            try {
                return writeTransaction(listOf(completion), useBatch = false).single() == 1
            } catch (failure: RolledBackBatchException) {
                if (failure.databaseFailure !is PessimisticLockingFailureException ||
                    attempt == MAX_LOCK_ATTEMPTS - 1
                ) {
                    throw failure
                }
                // Retry metadata only after a confirmed rollback; the user handler is never re-invoked here.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos((attempt + 1).toLong()))
            }
        }
        error("Unreachable completion retry state")
    }

    private fun writeTransaction(
        completions: List<JdbcOutboxCompletion>,
        useBatch: Boolean,
    ): IntArray =
        checkNotNull(
            transactions.execute {
                try {
                    val counts =
                        if (useBatch) {
                            batchUpdate(completions)
                        } else {
                            IntArray(completions.size) { index ->
                                jdbc.update(updateQuery) { statement -> bind(statement, completions[index]) }
                            }
                        }
                    validateUpdateCounts(counts, completions.size, useBatch)
                    counts
                } catch (failure: DataAccessException) {
                    if (failure is DataIntegrityViolationException || failure is PessimisticLockingFailureException) {
                        throw RolledBackBatchException(failure)
                    }
                    throw failure
                }
            },
        )

    private fun batchUpdate(completions: List<JdbcOutboxCompletion>): IntArray {
        val updater = bulkUpdater
        // A multi-row UPDATE must not collapse repeated logical updates of the same row.
        if (updater != null && completions.map { it.id }.toSet().size == completions.size) {
            return updater.update(completions)
        }
        return jdbc.batchUpdate(
            updateQuery,
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = completions.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) = bind(statement, completions[index])
            },
        )
    }

    private fun validateUpdateCounts(
        counts: IntArray,
        expectedSize: Int,
        useBatch: Boolean,
    ) {
        if (counts.size == expectedSize && counts.all { it == 0 || it == 1 }) return
        if (useBatch && counts.any { it == Statement.SUCCESS_NO_INFO }) throw InexactUpdateCountsException()
        error("Unexpected JDBC completion update counts: ${counts.contentToString()}")
    }

    private fun bind(
        statement: PreparedStatement,
        completion: JdbcOutboxCompletion,
    ) {
        statement.setString(1, OutboxRecordStatus.COMPLETED.name)
        if (completion.completedAt == null) {
            statement.setNull(2, Types.TIMESTAMP)
        } else {
            statement.setTimestamp(2, completion.completedAt.toTimestamp())
        }
        statement.setInt(3, completion.failureCount)
        statement.setString(4, completion.failureReason)
        statement.setTimestamp(5, completion.nextRetryAt.toTimestamp())
        statement.setString(6, completion.id)
    }
}
