package io.namastack.outbox.completion

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tests submission coordination with shutdown and caller interruption status.
 *
 * @author Roland Beisel
 */
class OutboxCompletionQueueTest {
    @Test
    fun `closing before insertion rejects the submission and allows restarting`() {
        val queue = OutboxCompletionQueue()
        queue.startAccepting()
        val submitted = CompletableFuture<CompletableFuture<Unit>?>()
        val submissionLock = ReflectionTestUtils.getField(queue, "submissionLock") as ReentrantLock
        val submittingThread =
            Thread {
                try {
                    submitted.complete(queue.submit(OutboxCompletion.Delete("late")))
                } catch (failure: Throwable) {
                    submitted.completeExceptionally(failure)
                }
            }

        try {
            // Hold the insertion lock so shutdown deterministically wins before insertion.
            submissionLock.withLock {
                submittingThread.start()
                await().atMost(Duration.ofSeconds(2)).until { submissionLock.hasQueuedThread(submittingThread) }
                queue.stopAccepting()
            }

            assertThat(submitted.get(2, TimeUnit.SECONDS)).isNull()
            assertThat(queue.hasPending()).isFalse()

            queue.startAccepting()
            val accepted = CompletableFuture.supplyAsync { queue.submit(OutboxCompletion.Delete("restarted")) }
            assertThat(accepted.get(2, TimeUnit.SECONDS)).isNotNull()
            assertThat(queue.poll(0)?.completion).isEqualTo(OutboxCompletion.Delete("restarted"))
        } finally {
            queue.stopAccepting()
            submittingThread.join(2_000)
        }
    }

    @Test
    fun `interrupted polling leaves the queue usable`() {
        val queue = OutboxCompletionQueue()
        queue.startAccepting()
        val polled = CompletableFuture<OutboxCompletionSubmission?>()
        val pollingThread =
            Thread {
                Thread.currentThread().interrupt()
                polled.complete(queue.poll(TimeUnit.SECONDS.toNanos(1)))
            }

        pollingThread.start()
        try {
            assertThat(polled.get(2, TimeUnit.SECONDS)).isNull()
            val accepted = queue.submit(OutboxCompletion.Delete("record"))
            assertThat(accepted).isNotNull()
            assertThat((queue.poll(0L)?.completion as OutboxCompletion.Delete).recordId).isEqualTo("record")
        } finally {
            queue.stopAccepting()
            pollingThread.join(2_000)
        }
    }

    @Test
    fun `submission preserves interruption and does not wait for pending writes`() {
        val queue = OutboxCompletionQueue()
        queue.startAccepting()
        val first = queue.submit(OutboxCompletion.Delete("first"))
        val submission = CompletableFuture<Pair<CompletableFuture<Unit>?, Boolean>>()
        val waitingThread =
            Thread {
                Thread.currentThread().interrupt()
                try {
                    val accepted = queue.submit(OutboxCompletion.Delete("second"))
                    submission.complete(accepted to Thread.currentThread().isInterrupted)
                } catch (failure: Throwable) {
                    submission.completeExceptionally(failure)
                }
            }
        waitingThread.start()

        try {
            val (accepted, interrupted) = submission.get(2, TimeUnit.SECONDS)
            assertThat(accepted).isNotNull()
            assertThat(interrupted).isTrue()
            assertThat((queue.poll(0L)?.completion as OutboxCompletion.Delete).recordId).isEqualTo("first")
            assertThat((queue.poll(0L)?.completion as OutboxCompletion.Delete).recordId).isEqualTo("second")
            assertThat(first).isNotNull()
        } finally {
            queue.stopAccepting()
            waitingThread.join(2_000)
        }
    }
}
