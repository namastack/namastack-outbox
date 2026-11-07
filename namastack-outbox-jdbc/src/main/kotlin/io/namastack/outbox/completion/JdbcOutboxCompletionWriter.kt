package io.namastack.outbox.completion

/**
 * Writes JDBC completion updates and deletions in separate batches.
 *
 * Successful results represent writes whose transactions committed. On a failed result, the
 * database operation either rolled back or its outcome could not be confirmed (for example, if
 * the connection failed while committing). Implementations can retry a failed batch in smaller
 * transactions to identify which actions failed. The writer may be called concurrently.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal interface JdbcOutboxCompletionWriter {
    /**
     * Writes processing-state updates for completed records.
     *
     * The returned results match [updates] by position. An exception means that individual
     * outcomes could not be determined and the caller must fail every update in the batch.
     *
     * @param updates Processing-state updates to write
     * @return Confirmed committed success or failure for each update
     * @throws Exception if individual outcomes cannot be determined
     */
    fun writeUpdates(updates: List<JdbcOutboxCompletion.Update>): List<Result<Unit>>

    /**
     * Deletes completed records.
     *
     * The returned results match [deletions] by position. An exception means that individual
     * outcomes could not be determined and the caller must fail every deletion in the batch.
     *
     * @param deletions Record deletions to write
     * @return Confirmed committed success or failure for each deletion
     * @throws Exception if individual outcomes cannot be determined
     */
    fun writeDeletions(deletions: List<JdbcOutboxCompletion.Delete>): List<Result<Unit>>
}
