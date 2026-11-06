package io.namastack.outbox.completion

import java.util.concurrent.CompletableFuture

/**
 * Completion submitted for writing together with the future awaited by its caller.
 *
 * @param completion Immutable completion action
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionSubmission(
    val completion: JdbcOutboxCompletion,
) {
    /** Future completed after the write finishes. */
    val resultFuture = CompletableFuture<Unit>()
}
