package io.namastack.outbox.completion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.config.OutboxCompletionAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskDecorator
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies shared batching configuration independently of any persistence module.
 *
 * @author Roland Beisel
 */
class OutboxCompletionAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskExecutionAutoConfiguration::class.java,
                    OutboxCompletionAutoConfiguration::class.java,
                ),
            ).withBean(OutboxRecordRepository::class.java, { mockk() })
            .withPropertyValues("namastack.outbox.completion-batching.enabled=true")

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    fun `completion writer uses virtual threads when enabled`() {
        val thread = completionThread("spring.threads.virtual.enabled=true", SimpleAsyncTaskExecutor::class.java)
        assertThat(Thread::class.java.getMethod("isVirtual").invoke(thread)).isEqualTo(true)
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    fun `completion writer uses platform threads when virtual threads are disabled`() {
        val thread = completionThread("spring.threads.virtual.enabled=false", ThreadPoolTaskExecutor::class.java)
        assertThat(Thread::class.java.getMethod("isVirtual").invoke(thread)).isEqualTo(false)
    }

    @Test
    @EnabledForJreRange(max = JRE.JAVA_17)
    fun `completion writer remains usable on Java 17 with the virtual thread property enabled`() {
        assertThat(completionThread("spring.threads.virtual.enabled=true", ThreadPoolTaskExecutor::class.java).name)
            .startsWith("outbox-completion-writer-")
    }

    private fun completionThread(
        property: String,
        executorType: Class<out TaskExecutor>,
    ): Thread {
        val writtenBy = CompletableFuture<Thread>()
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers {
            writtenBy.complete(Thread.currentThread())
            firstArg<List<OutboxCompletion.Delete>>().map { Result.success(Unit) }
        }
        runner
            .withPropertyValues(property)
            .withBean(OutboxCompletionWriter::class.java, { writer })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean("outboxCompletionExecutor")).isInstanceOf(executorType)
                context.getBean<OutboxCompletionPersistence>().deleteCompleted("record")
            }
        val thread = writtenBy.get(2, TimeUnit.SECONDS)
        thread.join(2_000)
        assertThat(thread.isAlive).isFalse()
        return thread
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `uses a supplied completion executor without taking ownership`(virtualThreads: Boolean) {
        val executor = ThreadPoolTaskExecutor().apply { initialize() }
        try {
            val writer = mockk<OutboxCompletionWriter>()
            every { writer.writeDeletions(any()) } returns listOf(Result.success(Unit))
            runner
                .withPropertyValues("spring.threads.virtual.enabled=$virtualThreads")
                .withBean("outboxCompletionExecutor", TaskExecutor::class.java, { executor })
                .withBean(OutboxCompletionWriter::class.java, { writer })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBean("outboxCompletionExecutor")).isSameAs(executor)
                    context.getBean<OutboxCompletionPersistence>().deleteCompleted("record")
                    context.getBean<OutboxCompletionBatcher>().stop()
                    assertThat(executor.threadPoolExecutor.isShutdown).isFalse()
                    verify(exactly = 1) { writer.writeDeletions(any()) }
                }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `platform batcher restarts while the previous executor task finishes cleanup`() {
        val cleaningUp = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decorator =
            TaskDecorator { task ->
                Runnable {
                    try {
                        task.run()
                    } finally {
                        cleaningUp.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers {
            firstArg<List<OutboxCompletion.Delete>>().map { Result.success(Unit) }
        }
        runner
            .withPropertyValues(
                "spring.threads.virtual.enabled=false",
            ).withBean(TaskDecorator::class.java, { decorator })
            .withBean(OutboxCompletionWriter::class.java, { writer })
            .run { context ->
                assertThat(context).hasNotFailed()
                val batcher = context.getBean<OutboxCompletionBatcher>()
                try {
                    batcher.stop()
                    assertThat(cleaningUp.await(2, TimeUnit.SECONDS)).isTrue()

                    batcher.start()
                    val pending = checkNotNull(batcher.submit(OutboxCompletion.Delete("after-restart")))
                    assertThat(pending.isDone).isFalse()
                    release.countDown()

                    pending.get(2, TimeUnit.SECONDS)
                    assertThat(batcher.isRunning).isTrue()
                } finally {
                    release.countDown()
                }
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @ExtendWith(OutputCaptureExtension::class)
    fun `context stops and restarts the completion worker without lifecycle timeouts`(
        virtualThreads: Boolean,
        output: CapturedOutput,
    ) {
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers {
            firstArg<List<OutboxCompletion.Delete>>().map { Result.success(Unit) }
        }
        runner
            .withPropertyValues("spring.threads.virtual.enabled=$virtualThreads")
            .withBean(
                "lifecycleProcessor",
                DefaultLifecycleProcessor::class.java,
                { DefaultLifecycleProcessor().apply { setTimeoutPerShutdownPhase(1_000) } },
            ).withBean(OutboxCompletionWriter::class.java, { writer })
            .run { context ->
                assertThat(context).hasNotFailed()
                val batcher = context.getBean<OutboxCompletionBatcher>()
                val persistence = context.getBean<OutboxCompletionPersistence>()
                persistence.deleteCompleted("before-restart")

                context.stop()
                assertThat(batcher.isRunning).isFalse()
                assertThat(output.all).doesNotContain("bean still running after timeout")

                context.start()
                assertThat(batcher.isRunning).isTrue()
                persistence.deleteCompleted("after-restart")
                verify(exactly = 2) { writer.writeDeletions(any()) }
            }
    }

    @Test
    fun `enabled batching requires a storage writer`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("OutboxCompletionWriter")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `context shutdown lets an accepted write finish after the shutdown timeout`(virtualThreads: Boolean) {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers {
            writing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(Result.success(Unit))
        }
        var pending: CompletableFuture<Void>? = null
        try {
            runner
                .withPropertyValues(
                    "spring.threads.virtual.enabled=$virtualThreads",
                    "namastack.outbox.processing.shutdown-timeout=10ms",
                ).withBean(OutboxCompletionWriter::class.java, { writer })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val persistence = context.getBean<OutboxCompletionPersistence>()
                    pending = CompletableFuture.runAsync { persistence.deleteCompleted("record") }
                    assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
                }
            assertThat(checkNotNull(pending).isDone).isFalse()
        } finally {
            release.countDown()
        }
        checkNotNull(pending).get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `a storage writer reuses the core batching lifecycle`() {
        val deleted = CopyOnWriteArrayList<String>()
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers {
            firstArg<List<OutboxCompletion.Delete>>().map {
                deleted.add(it.recordId)
                Result.success(Unit)
            }
        }
        runner.withBean(OutboxCompletionWriter::class.java, { writer }).run { context ->
            assertThat(context).hasNotFailed()
            val persistence = context.getBean<OutboxCompletionBatchingPersistence>()
            val batcher = context.getBean<OutboxCompletionBatcher>()
            assertThat(batcher.isRunning).isTrue()
            persistence.deleteCompleted("record")
            assertThat(deleted).containsExactly("record")
            verify(exactly = 0) { context.getBean<OutboxRecordRepository>().deleteById(any()) }
            batcher.stop()
            assertThat(batcher.isRunning).isFalse()
        }
    }
}
