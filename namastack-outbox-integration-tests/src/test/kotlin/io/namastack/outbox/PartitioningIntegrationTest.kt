package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@DataJpaTest(showSql = false)
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
@EnableConfigurationProperties(OutboxProperties::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PartitioningIntegrationTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    @Autowired
    private lateinit var instanceRepository: OutboxInstanceRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var outboxProperties: OutboxProperties

    @AfterEach
    fun setUp() {
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `only one instance claims all partition when concurrent bootstrapping two instances`() {
        val instanceId1 = "instance-1"
        val instanceId2 = "instance-2"

        val partitionCoordinator1 = setupPartitionCoordinator(instanceId1)
        val partitionCoordinator2 = setupPartitionCoordinator(instanceId2)

        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)

        // setup two instances and start processing concurrently at exactly the same time
        executor.submit {
            barrier.await()
            partitionCoordinator1.rebalance()
        }
        executor.submit {
            barrier.await()
            partitionCoordinator2.rebalance()
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
        val instanceId1 = "instance-1"
        setupPartitionCoordinator(instanceId1).rebalance()

        // remove bootstrap instance so all its partitions become stale
        instanceRepository.deleteById(instanceId1)

        val instanceId2 = "instance-2"
        val instanceId3 = "instance-3"

        val partitionCoordinator2 = setupPartitionCoordinator(instanceId2)
        val partitionCoordinator3 = setupPartitionCoordinator(instanceId3)

        val executor = Executors.newScheduledThreadPool(2)

        // rebalance both instances at fixed rate
        executor.scheduleAtFixedRate({
            partitionCoordinator2.rebalance()
        }, 0, 1, SECONDS)
        executor.scheduleAtFixedRate({
            partitionCoordinator3.rebalance()
        }, 0, 1, SECONDS)

        // await stable distribution: each new instance should own exactly half (128) of 256 partitions
        await()
            .atMost(5, SECONDS)
            .untilAsserted {
                val total = partitionAssignmentRepository.findAll().size
                val count1 = partitionAssignmentRepository.findByInstanceId(instanceId1).size
                val count2 = partitionAssignmentRepository.findByInstanceId(instanceId2).size
                val count3 = partitionAssignmentRepository.findByInstanceId(instanceId3).size
                val unassigned = partitionAssignmentRepository.findAll().count { it.instanceId == null }

                assertThat(count1).isEqualTo(0)
                assertThat(count2).isEqualTo(128)
                assertThat(count3).isEqualTo(128)
                assertThat(total).isEqualTo(256)
                assertThat(unassigned).isEqualTo(0)
            }
    }

    private fun setupPartitionCoordinator(instanceId: String): PartitionCoordinator {
        val instanceRegistry =
            OutboxInstanceRegistry(
                currentInstanceId = instanceId,
                instanceRepository = instanceRepository,
                properties = outboxProperties,
                clock = clock,
            )

        instanceRegistry.registerInstance()

        return PartitionCoordinator(
            instanceRegistry = instanceRegistry,
            partitionAssignmentRepository = partitionAssignmentRepository,
            clock = Clock.systemDefaultZone(),
        )
    }

    private fun cleanupTables() {
        transactionTemplate.executeNonNull {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity ").executeUpdate()
            entityManager.flush()
        }
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
