package io.namastack.outbox.partition

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.instance.OutboxInstanceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.SECONDS

@DisplayName("PartitionCoordinator")
class PartitionCoordinatorTest {
    private val instanceRegistry = mockk<OutboxInstanceRegistry>()
    private val partitionAssignmentRepository = mockk<PartitionAssignmentRepository>()
    private val partitionAssignmentCache = mockk<PartitionAssignmentCache>(relaxed = true)
    private val clock = Clock.systemUTC()
    private val taskScheduler = mockk<TaskScheduler>()
    private val scheduledRebalance = mockk<ScheduledFuture<*>>()

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var partitionCoordinator: PartitionCoordinator

    @BeforeEach
    fun setUp() {
        listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()

        val logger = LoggerFactory.getLogger(PartitionCoordinator::class.java) as Logger
        logger.addAppender(listAppender)
        logger.level = Level.DEBUG

        every { instanceRegistry.getCurrentInstanceId() } returns "instance-1"
        every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
        every { partitionAssignmentRepository.findByInstanceId(any()) } returns emptySet()
        every { partitionAssignmentRepository.findAll() } returns emptySet()
        every { partitionAssignmentRepository.saveAll(any()) } returns Unit
        every { partitionAssignmentCache.getAssignedPartitionNumbers(any()) } returns emptySet()
        every { taskScheduler.clock } returns clock
        every {
            taskScheduler.scheduleWithFixedDelay(any<Runnable>(), any<Instant>(), REBALANCE_INTERVAL)
        } returns scheduledRebalance
        every { scheduledRebalance.cancel(false) } returns true

        partitionCoordinator =
            PartitionCoordinator(
                instanceRegistry = instanceRegistry,
                partitionAssignmentRepository = partitionAssignmentRepository,
                partitionAssignmentCache = partitionAssignmentCache,
                clock = clock,
                taskScheduler = taskScheduler,
                rebalanceInterval = REBALANCE_INTERVAL,
                observationRegistry = { ObservationRegistry.NOOP },
            )
    }

    @Nested
    @DisplayName("Lifecycle")
    inner class LifecycleTests {
        @Test
        fun `perform initial rebalance and schedule periodic executions once`() {
            partitionCoordinator.start()
            partitionCoordinator.start()

            assertThat(partitionCoordinator.isRunning).isTrue()
            assertThat(partitionCoordinator.phase).isEqualTo(1)
            verify(exactly = 1) { partitionAssignmentRepository.findAll() }
            verify(exactly = 1) {
                taskScheduler.scheduleWithFixedDelay(any<Runnable>(), any<Instant>(), REBALANCE_INTERVAL)
            }
        }

        @Test
        fun `cancel periodic execution when stopped`() {
            partitionCoordinator.start()

            partitionCoordinator.stop()
            partitionCoordinator.stop()

            assertThat(partitionCoordinator.isRunning).isFalse()
            verify(exactly = 1) { scheduledRebalance.cancel(false) }
        }

        @Test
        fun `scheduled execution invokes rebalance`() {
            val runnable = slot<Runnable>()
            every {
                taskScheduler.scheduleWithFixedDelay(capture(runnable), any<Instant>(), REBALANCE_INTERVAL)
            } returns scheduledRebalance
            partitionCoordinator.start()
            verify(exactly = 1) { partitionAssignmentRepository.findAll() }

            runnable.captured.run()

            verify(exactly = 2) { partitionAssignmentRepository.findAll() }
        }

        @Test
        fun `scheduled execution does not rebalance after stop`() {
            val runnable = slot<Runnable>()
            every {
                taskScheduler.scheduleWithFixedDelay(capture(runnable), any<Instant>(), REBALANCE_INTERVAL)
            } returns scheduledRebalance
            partitionCoordinator.start()

            partitionCoordinator.stop()
            runnable.captured.run()

            verify(exactly = 1) { partitionAssignmentRepository.findAll() }
        }

        @Test
        fun `stop waits for an active rebalance`() {
            val runnable = slot<Runnable>()
            every {
                taskScheduler.scheduleWithFixedDelay(capture(runnable), any<Instant>(), REBALANCE_INTERVAL)
            } returns scheduledRebalance
            partitionCoordinator.start()

            val rebalanceStarted = CountDownLatch(1)
            val allowRebalanceToFinish = CountDownLatch(1)
            val cancellationAttempted = CountDownLatch(1)
            every { partitionAssignmentRepository.findAll() } answers {
                rebalanceStarted.countDown()
                allowRebalanceToFinish.await(2, SECONDS)
                emptySet()
            }
            every { scheduledRebalance.cancel(false) } answers {
                cancellationAttempted.countDown()
                false
            }

            val executor = Executors.newFixedThreadPool(2)
            try {
                val rebalanceFuture = executor.submit(runnable.captured)
                assertThat(rebalanceStarted.await(2, SECONDS)).isTrue()

                val stopFuture = executor.submit(partitionCoordinator::stop)
                assertThat(cancellationAttempted.await(2, SECONDS)).isTrue()
                assertThat(stopFuture.isDone).isFalse()

                allowRebalanceToFinish.countDown()
                rebalanceFuture.get(2, SECONDS)
                stopFuture.get(2, SECONDS)
                assertThat(partitionCoordinator.isRunning).isFalse()
            } finally {
                allowRebalanceToFinish.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Nested
    @DisplayName("Get Assigned Partition Numbers")
    inner class GetAssignedPartitionNumbers {
        @Test
        fun `return assigned partition numbers for current instance`() {
            every {
                partitionAssignmentCache.getAssignedPartitionNumbers("instance-1")
            } returns setOf(0, 1)

            val result = partitionCoordinator.getAssignedPartitionNumbers()

            assertThat(result).containsExactlyInAnyOrder(0, 1)
            verify(exactly = 1) { partitionAssignmentCache.getAssignedPartitionNumbers("instance-1") }
        }

        @Test
        fun `return empty set when no partitions assigned`() {
            every { partitionAssignmentCache.getAssignedPartitionNumbers("instance-1") } returns emptySet()

            val result = partitionCoordinator.getAssignedPartitionNumbers()

            assertThat(result).isEmpty()
        }
    }

    @Nested
    @DisplayName("Get Partition Context")
    inner class GetPartitionContextTests {
        @Test
        fun `return partition context with active instances and assignments`() {
            val assignment1 = PartitionAssignment(0, "instance-1", Instant.now())
            val assignment2 = PartitionAssignment(1, "instance-2", Instant.now())

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns setOf(assignment1, assignment2)

            val context = partitionCoordinator.getPartitionContext()

            assertThat(context).isNotNull
            assertThat(context.currentInstanceId).isEqualTo("instance-1")
            assertThat(context.activeInstanceIds).containsExactly("instance-1", "instance-2")
            assertThat(context.partitionAssignments).containsExactly(assignment1, assignment2)
        }

        @Test
        fun `throw exception when no active instances`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            assertThatThrownBy {
                partitionCoordinator.getPartitionContext()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessage("No active instance ids found")
        }

        @Test
        fun `return context with empty assignments when no assignments exist`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns emptySet()

            val context = partitionCoordinator.getPartitionContext()

            assertThat(context).isNotNull
            assertThat(context.partitionAssignments).isEmpty()
        }
    }

    @Nested
    @DisplayName("Rebalance")
    inner class RebalanceTests {
        @Test
        fun `bootstrap all partitions when no assignments exist`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")
            every { partitionAssignmentRepository.findAll() } returns emptySet()

            partitionCoordinator.rebalance()

            verify { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `throw exception when no active instances during rebalance`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            assertThatThrownBy {
                partitionCoordinator.rebalance()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessage("No active instance ids found")
        }

        @Test
        fun `handle bootstrap failure gracefully`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")
            every { partitionAssignmentRepository.findAll() } returns emptySet()
            every { partitionAssignmentRepository.saveAll(any()) } throws
                RuntimeException("Database error")

            partitionCoordinator.rebalance()

            verify(exactly = 1) { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `claim stale partitions when owned less than target`() {
            val ownedAssignment = PartitionAssignment(0, "instance-1", Instant.now())
            val staleAssignments =
                (1..150)
                    .map { partitionNumber ->
                        PartitionAssignment(partitionNumber, "instance-old", Instant.now())
                    }.toSet()
            val allAssignments = setOf(ownedAssignment) + staleAssignments

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns allAssignments

            partitionCoordinator.rebalance()

            verify { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `release surplus partitions when owned more than target`() {
            val assignments =
                (0..150)
                    .map { partitionNumber ->
                        PartitionAssignment(partitionNumber, "instance-1", Instant.now())
                    }.toSet()

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns assignments

            partitionCoordinator.rebalance()

            verify { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `handle claim stale partitions failure gracefully`() {
            val ownedAssignment = PartitionAssignment(0, "instance-1", Instant.now())
            val staleAssignments =
                (1..150)
                    .map { partitionNumber ->
                        PartitionAssignment(partitionNumber, "instance-old", Instant.now())
                    }.toSet()
            val allAssignments = setOf(ownedAssignment) + staleAssignments

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns allAssignments
            every { partitionAssignmentRepository.saveAll(any()) } throws
                RuntimeException("Database error")

            partitionCoordinator.rebalance()

            verify(exactly = 1) { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `handle release surplus partitions failure gracefully`() {
            val assignments =
                (0..150)
                    .map { partitionNumber ->
                        PartitionAssignment(partitionNumber, "instance-1", Instant.now())
                    }.toSet()

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            every { partitionAssignmentRepository.findAll() } returns assignments
            every { partitionAssignmentRepository.saveAll(any()) } throws
                RuntimeException("Database error")

            partitionCoordinator.rebalance()

            verify(exactly = 1) { partitionAssignmentRepository.saveAll(any()) }
        }

        @Test
        fun `bootstrap logs success message when initialized`() {
            every { instanceRegistry.getCurrentInstanceId() } returns "i1"
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("i1")
            every { partitionAssignmentRepository.saveAll(any()) } returns Unit

            partitionCoordinator.rebalance()

            val messages = listAppender.list.map { it.formattedMessage }
            assertThat(messages).anyMatch { it.contains("Successfully bootstrapped") }
        }

        @Test
        fun `bootstrap logs message when already initialized`() {
            every { instanceRegistry.getCurrentInstanceId() } returns "i1"
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("i1")
            every { partitionAssignmentRepository.saveAll(any()) } throws
                DataIntegrityViolationException("Already initialized")

            partitionCoordinator.rebalance()

            val messages = listAppender.list.map { it.formattedMessage }
            assertThat(messages).anyMatch { it.contains("Another instance initialized partitions, skipping bootstrap") }
        }

        @Test
        fun `bootstrap logs warn message on error while initializing`() {
            every { instanceRegistry.getCurrentInstanceId() } returns "i1"
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("i1")
            every { partitionAssignmentRepository.saveAll(any()) } throws
                IllegalStateException("Database error")

            partitionCoordinator.rebalance()

            val messages = listAppender.list.map { it.formattedMessage }
            assertThat(messages).anyMatch { it.contains("Failed to bootstrap partitions for instance") }
        }
    }

    private companion object {
        val REBALANCE_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
