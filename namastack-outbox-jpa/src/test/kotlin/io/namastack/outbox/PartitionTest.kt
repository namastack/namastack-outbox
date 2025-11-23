package io.namastack.outbox

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.namastack.outbox.PartitionTest.PartitionTestConfiguration
import io.namastack.outbox.partition.OutboxRebalanceSignal
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.FixedDelayRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration.ofSeconds
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@DataJpaTest(showSql = false)
@ImportAutoConfiguration(
    OutboxCoreAutoConfiguration::class,
    JpaOutboxAutoConfiguration::class,
)
@Import(PartitionTestConfiguration::class)
class PartitionTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var outboxProperties: OutboxProperties

    @Autowired
    private lateinit var instanceRepository: OutboxInstanceRepository

    @Autowired
    private lateinit var outboxRecordProcessor: OutboxRecordProcessor

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `only one instance claims all partition when concurrent bootstrapping two instances`() {
        val instanceId1 = "instance-1"
        val instanceId2 = "instance-2"

        val rebalanceSignalForInstance1 = OutboxRebalanceSignal().apply { request() }
        val rebalanceSignalForInstance2 = OutboxRebalanceSignal().apply { request() }

        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)

        // setup two instances and start processing concurrently at exactly the same time
        executor.submit {
            barrier.await()
            setupOutboxProcessingScheduler(instanceId1, rebalanceSignalForInstance1).process()
        }
        executor.submit {
            barrier.await()
            setupOutboxProcessingScheduler(instanceId2, rebalanceSignalForInstance2).process()
        }

        await()
            .atMost(5, SECONDS)
            .untilAsserted {
                val partitionCountInstance1 = partitionAssignmentRepository.findByInstanceId(instanceId1).size
                val partitionCountInstance2 = partitionAssignmentRepository.findByInstanceId(instanceId2).size

                // assert that exactly one of the instances claimed all partitions
                assertThat(Pair(partitionCountInstance1, partitionCountInstance2)).satisfiesAnyOf(
                    {
                        assertThat(partitionCountInstance1).isEqualTo(256)
                        assertThat(partitionCountInstance2).isEqualTo(0)
                    },
                    {
                        assertThat(partitionCountInstance1).isEqualTo(0)
                        assertThat(partitionCountInstance2).isEqualTo(256)
                    },
                )
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent stale partition claim leads to consistent 128-128 distribution`() {
        // bootstrap with a single instance owning all partitions
        val instanceId1 = "instance-1"
        val rebalanceSignalForInstance1 = OutboxRebalanceSignal().apply { request() }
        setupOutboxProcessingScheduler(instanceId1, rebalanceSignalForInstance1).process()

        // remove bootstrap instance so all its partitions become stale
        instanceRepository.deleteById(instanceId1)

        // prepare two new instances that will concurrently attempt to claim stale partitions
        val instanceId2 = "instance-2"
        val instanceId3 = "instance-3"

        val rebalanceSignalForInstance2 = OutboxRebalanceSignal().apply { request() }
        val rebalanceSignalForInstance3 = OutboxRebalanceSignal().apply { request() }

        val scheduler2 = setupOutboxProcessingScheduler(instanceId2, rebalanceSignalForInstance2)
        val scheduler3 = setupOutboxProcessingScheduler(instanceId3, rebalanceSignalForInstance3)

        val executor = Executors.newScheduledThreadPool(2)

        // rebalance both instances at fixed rate
        executor.scheduleAtFixedRate({
            rebalanceSignalForInstance2.request()
            scheduler2.process()
        }, 0, 1, SECONDS)
        executor.scheduleAtFixedRate({
            rebalanceSignalForInstance3.request()
            scheduler3.process()
        }, 0, 1, SECONDS)

        // await stable distribution: each new instance should own exactly half (128) of 256 partitions
        await()
            .atMost(5, SECONDS)
            .untilAsserted {
                val total = partitionAssignmentRepository.findAll().size
                val count2 = partitionAssignmentRepository.findByInstanceId(instanceId2).size
                val count3 = partitionAssignmentRepository.findByInstanceId(instanceId3).size
                val unassigned = partitionAssignmentRepository.findAll().count { it.instanceId == null }

                assertThat(total).isEqualTo(256)
                assertThat(count2).isEqualTo(128)
                assertThat(count3).isEqualTo(128)
                assertThat(unassigned).isEqualTo(0)
            }
    }

    private fun setupOutboxProcessingScheduler(
        instanceId: String,
        rebalanceSignal: OutboxRebalanceSignal,
    ): OutboxProcessingScheduler {
        val instanceRegistry =
            OutboxInstanceRegistry(
                currentInstanceId = instanceId,
                instanceRepository = instanceRepository,
                properties = outboxProperties,
                clock = clock,
            )

        instanceRegistry.registerInstance()

        val partitionCoordinator =
            PartitionCoordinator(
                instanceRegistry = instanceRegistry,
                partitionAssignmentRepository = partitionAssignmentRepository,
            )

        return OutboxProcessingScheduler(
            recordProcessor = outboxRecordProcessor,
            recordRepository = outboxRecordRepository,
            clock = clock,
            partitionCoordinator = partitionCoordinator,
            rebalanceSignal = rebalanceSignal,
            taskExecutor = SimpleAsyncTaskExecutor(),
            properties = outboxProperties,
            retryPolicy = FixedDelayRetryPolicy(ofSeconds(1)),
        )
    }

    @TestConfiguration
    class PartitionTestConfiguration {
        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        @Primary
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            every { mock.registerInstance() } just Runs
            every { mock.getCurrentInstanceId() } returns "mock-instance"
            every { mock.getActiveInstanceIds() } returns setOf("mock-instance")
            return mock
        }
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
