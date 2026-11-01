package io.namastack.outbox

/**
 * Executes completion updates inside the caller's transaction.
 *
 * Results correspond to the input positions and must be exact zero-or-one row counts. Implementations
 * never publish commit acknowledgements; the enclosing writer owns commit and failure handling.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal fun interface JdbcOutboxBulkCompletionUpdater {
    /** Returns exact update counts without committing or acknowledging the requests. */
    fun update(completions: List<JdbcOutboxCompletion>): IntArray
}
