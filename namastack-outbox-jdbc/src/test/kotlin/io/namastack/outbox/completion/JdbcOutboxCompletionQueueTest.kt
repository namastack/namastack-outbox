package io.namastack.outbox.completion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Tests that queue backpressure preserves accepted completions and caller interruption status.
 *
 * @author Roland Beisel
 */
class JdbcOutboxCompletionQueueTest {
    @Test
    fun `an interrupted waiter submits after capacity frees and restores its interrupt status`() {
        val queue = JdbcOutboxCompletionQueue(1)
        queue.startAccepting()
        val first = queue.submit(JdbcOutboxCompletion.Delete("first"))
        val submission = CompletableFuture<Pair<CompletableFuture<Unit>?, Boolean>>()
        val waitingThread =
            Thread {
                Thread.currentThread().interrupt()
                try {
                    val accepted = queue.submit(JdbcOutboxCompletion.Delete("second"))
                    submission.complete(accepted to Thread.currentThread().isInterrupted)
                } catch (failure: Throwable) {
                    submission.completeExceptionally(failure)
                }
            }
        waitingThread.start()

        try {
            assertThatThrownBy { submission.get(100, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
            assertThat((queue.poll(0L)?.completion as JdbcOutboxCompletion.Delete).recordId).isEqualTo("first")
            val (accepted, interrupted) = submission.get(2, TimeUnit.SECONDS)
            assertThat(accepted).isNotNull()
            assertThat(interrupted).isTrue()
            assertThat((queue.poll(0L)?.completion as JdbcOutboxCompletion.Delete).recordId).isEqualTo("second")
            assertThat(first).isNotNull()
        } finally {
            queue.stopAccepting()
            waitingThread.join(2_000)
        }
    }
}
