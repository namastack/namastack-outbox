package io.namastack.outbox.completion

/**
 * Writes a batch of completed record actions and reports the outcome of each action.
 *
 * Successful results must represent committed writes. Failed results must represent actions
 * that were not committed. Implementations can retry a failed batch in smaller transactions
 * to identify which actions failed. The writer may be called concurrently.
 *
 * @since 1.12.0
 */
internal fun interface JdbcOutboxCompletionWriter {
    /**
     * Writes the supplied actions.
     *
     * The returned list has one result per action, in the same order as [completions]. If the
     * entire call fails before individual outcomes can be determined, the method throws and
     * the caller treats every action as failed.
     *
     * @param completions Actions to write
     * @return Committed success or failure for each action
     */
    fun write(completions: List<JdbcOutboxCompletion>): List<Result<Unit>>
}
