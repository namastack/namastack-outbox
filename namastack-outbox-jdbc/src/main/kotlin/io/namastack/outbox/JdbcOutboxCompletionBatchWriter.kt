package io.namastack.outbox

import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.query.JdbcOutboxRecordQueries
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Statement

/**
 * Writes completion actions with the record repository's update and delete statements.
 *
 * If a batch transaction fails, each action is retried in its own transaction so successful
 * records can still be acknowledged. The caller must invoke this writer outside an existing
 * transaction.
 *
 * @param jdbc JDBC template used to execute the batch statements
 * @param transactions Transaction template used for each write attempt
 * @param queries Supplies the record statements on first use
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionBatchWriter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transactions: TransactionTemplate,
    queries: () -> JdbcOutboxRecordQueries,
) : OutboxCompletionWriter {
    private val sql by lazy(queries)

    override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> =
        writeBatch(updates) { batch ->
            jdbc.batchUpdate(
                sql.updateProcessingState,
                Array(batch.size) { index -> batch[index].parameters() },
            )
        }

    override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> =
        writeBatch(deletions) { batch ->
            jdbc.batchUpdate(
                sql.deleteById,
                Array<SqlParameterSource>(batch.size) { index ->
                    MapSqlParameterSource("id", batch[index].recordId)
                },
            )
        }

    private fun <T> writeBatch(
        completions: List<T>,
        execute: (List<T>) -> IntArray,
    ): List<Result<Unit>> {
        if (completions.isEmpty()) return emptyList()

        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Completion batches must be written outside an existing transaction"
        }

        return try {
            writeInTransaction(completions, execute)
            completions.map { Result.success(Unit) }
        } catch (_: Exception) {
            completions.map { completion ->
                try {
                    writeInTransaction(listOf(completion), execute)
                    Result.success(Unit)
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
        }
    }

    private fun <T> writeInTransaction(
        completions: List<T>,
        execute: (List<T>) -> IntArray,
    ) {
        transactions.executeWithoutResult {
            val counts = execute(completions)
            check(counts.size == completions.size && counts.all { it >= 0 || it == Statement.SUCCESS_NO_INFO }) {
                "Completion batch did not execute every statement"
            }
        }
    }

    private fun OutboxCompletion.Update.parameters(): SqlParameterSource =
        MapSqlParameterSource()
            .addValue("id", recordId)
            .addValue("status", status.name)
            .addValue("completedAt", completedAt?.toTimestamp())
            .addValue("failureCount", failureCount)
            .addValue("failureReason", failureReason)
            .addValue("nextRetryAt", nextRetryAt.toTimestamp())
}
