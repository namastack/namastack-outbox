package io.namastack.outbox

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SqlArrayValue
import java.time.Instant

/**
 * Updates independent completion records with one PostgreSQL statement.
 *
 * Parameter arrays keep the statement shape stable across batch sizes. Returned input ordinals
 * identify missing records without assuming an order for UPDATE RETURNING. Instants are bound as
 * explicit UTC strings so JDBC array conversion cannot apply the JVM or database session timezone.
 *
 * This class only executes SQL. [JdbcOutboxCompletionWriter] confirms results after commit and
 * retains responsibility for rollback recovery. Statement-level triggers observe one statement
 * per batch; enabling this strategy requires accepting that difference from individual updates.
 *
 * @param jdbc JDBC operations participating in the writer's transaction
 * @param tableName Fully resolved and trusted record table name
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxBulkCompletionUpdater(
    private val jdbc: JdbcOperations,
    tableName: String,
) : JdbcOutboxBulkCompletionUpdater {
    private val updateQuery =
        """
        UPDATE $tableName AS stored
        SET status = 'COMPLETED', completed_at = batch.completed_at,
            failure_count = batch.failure_count, failure_reason = batch.failure_reason,
            next_retry_at = batch.next_retry_at
        FROM unnest(
            CAST(? AS varchar[]), CAST(? AS timestamptz[]), CAST(? AS integer[]),
            CAST(? AS varchar[]), CAST(? AS timestamptz[])
        ) WITH ORDINALITY AS batch(record_id, completed_at, failure_count, failure_reason, next_retry_at, ordinal)
        WHERE stored.id = batch.record_id
        RETURNING batch.ordinal
        """.trimIndent()

    override fun update(completions: List<JdbcOutboxCompletion>): IntArray {
        val counts = IntArray(completions.size)
        val ordinals =
            jdbc.query(
                updateQuery,
                RowMapper { rows, _ -> rows.getInt(1) },
                arrayValue("varchar", completions.map { it.id }),
                arrayValue("timestamptz", completions.map { it.completedAt?.toJdbcTimestampText() }),
                arrayValue("integer", completions.map { it.failureCount }),
                arrayValue("varchar", completions.map { it.failureReason }),
                arrayValue("timestamptz", completions.map { it.nextRetryAt.toJdbcTimestampText() }),
            )
        ordinals.forEach { ordinal ->
            check(ordinal in 1..counts.size && counts[ordinal - 1] == 0) {
                "Unexpected PostgreSQL completion ordinal: $ordinal"
            }
            counts[ordinal - 1] = 1
        }
        return counts
    }

    /** Matches JDBC rounding and omits ISO's leading plus sign for expanded PostgreSQL years. */
    private fun Instant.toJdbcTimestampText(): String {
        val roundedNanos = ((nano + 500L) / 1_000L) * 1_000L
        val rounded =
            if (roundedNanos == nano.toLong()) {
                this
            } else {
                Instant.ofEpochSecond(epochSecond, roundedNanos)
            }
        return rounded.toString().removePrefix("+")
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> arrayValue(
        typeName: String,
        elements: List<T?>,
    ): SqlArrayValue {
        // JDBC supports SQL NULL array elements; Spring's vararg annotation is stricter.
        return SqlArrayValue(typeName, *(elements.toTypedArray() as Array<Any>))
    }
}
