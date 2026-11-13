package io.namastack.outbox.completion

/**
 * Writes processing-state updates and record deletions in separate batches.
 *
 * Successful results represent writes whose transactions committed. On a failed result, the
 * database operation either rolled back or its outcome could not be confirmed (for example, if
 * the connection failed while committing). Implementations can retry a failed batch in smaller
 * transactions to identify which actions failed. Each runtime calls its writer from a single worker.
 *
 * @author Roland Beisel
 * @since 2.0.0
 */
interface OutboxCompletionWriter {
    /**
     * Writes processing-state updates after success, retry scheduling, or permanent failure.
     *
     * The returned results match [updates] by position. An exception means that individual
     * outcomes could not be determined and the caller must fail every update in the batch.
     *
     * @param updates Processing-state updates to write
     * @return Confirmed committed success or failure for each update
     * @throws Exception if individual outcomes cannot be determined
     */
    fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>>

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
    fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>>
}
