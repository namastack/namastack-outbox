package io.namastack.outbox

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.namastack.outbox.PartitionTest.PartitionTestConfiguration
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

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
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    private lateinit var instanceRegistry1: OutboxInstanceRegistry
    private lateinit var instanceRegistry2: OutboxInstanceRegistry
    private lateinit var instanceRegistry3: OutboxInstanceRegistry
    private lateinit var instanceRegistry4: OutboxInstanceRegistry

    private lateinit var partitionCoordinator1: PartitionCoordinator
    private lateinit var partitionCoordinator2: PartitionCoordinator
    private lateinit var partitionCoordinator3: PartitionCoordinator
    private lateinit var partitionCoordinator4: PartitionCoordinator

    @BeforeEach
    fun setUp() {
        instanceRegistry1 = OutboxInstanceRegistry(instanceRepository, outboxProperties, clock, "instance-1")
        instanceRegistry2 = OutboxInstanceRegistry(instanceRepository, outboxProperties, clock, "instance-2")
        instanceRegistry3 = OutboxInstanceRegistry(instanceRepository, outboxProperties, clock, "instance-3")
        instanceRegistry4 = OutboxInstanceRegistry(instanceRepository, outboxProperties, clock, "instance-4")

        partitionCoordinator1 = PartitionCoordinator(instanceRegistry1, partitionAssignmentRepository)
        partitionCoordinator2 = PartitionCoordinator(instanceRegistry2, partitionAssignmentRepository)
        partitionCoordinator3 = PartitionCoordinator(instanceRegistry3, partitionAssignmentRepository)
        partitionCoordinator4 = PartitionCoordinator(instanceRegistry4, partitionAssignmentRepository)

        removeInstance(instanceRegistry1.getCurrentInstanceId())
        removeInstance(instanceRegistry2.getCurrentInstanceId())
        removeInstance(instanceRegistry3.getCurrentInstanceId())
        removeInstance(instanceRegistry4.getCurrentInstanceId())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun test() {
        instanceRegistry1.registerInstance()
        partitionCoordinator1.rebalance()

        var assignments = partitionAssignmentRepository.findAll()
        assertThat(assignments.count { it.instanceId == instanceRegistry1.getCurrentInstanceId() }).isEqualTo(256)

        instanceRegistry2.registerInstance()
        instanceRegistry3.registerInstance()

        partitionCoordinator1.rebalance()
        partitionCoordinator2.rebalance()
        partitionCoordinator3.rebalance()

        assignments = partitionAssignmentRepository.findAll()
        assertThat(assignments.count { it.instanceId == instanceRegistry1.getCurrentInstanceId() }).isEqualTo(86)
        assertThat(assignments.count { it.instanceId == instanceRegistry2.getCurrentInstanceId() }).isEqualTo(85)
        assertThat(assignments.count { it.instanceId == instanceRegistry3.getCurrentInstanceId() }).isEqualTo(85)

        removeInstance("instance-3")
        partitionCoordinator1.rebalance()
        partitionCoordinator2.rebalance()

        assignments = partitionAssignmentRepository.findAll()
        assertThat(assignments.count { it.instanceId == instanceRegistry1.getCurrentInstanceId() }).isEqualTo(128)
        assertThat(assignments.count { it.instanceId == instanceRegistry2.getCurrentInstanceId() }).isEqualTo(128)

        instanceRegistry4.registerInstance()

        partitionCoordinator4.rebalance()
        partitionCoordinator1.rebalance()
        partitionCoordinator2.rebalance()

        partitionCoordinator1.rebalance()
        partitionCoordinator2.rebalance()
        partitionCoordinator4.rebalance()

        assignments = partitionAssignmentRepository.findAll()
        assertThat(assignments.count { it.instanceId == instanceRegistry1.getCurrentInstanceId() }).isEqualTo(86)
        assertThat(assignments.count { it.instanceId == instanceRegistry2.getCurrentInstanceId() }).isEqualTo(85)
        assertThat(assignments.count { it.instanceId == instanceRegistry4.getCurrentInstanceId() }).isEqualTo(85)
    }

    private fun removeInstance(instanceId: String) {
        instanceRepository.deleteById(instanceId)
    }

    @TestConfiguration
    class PartitionTestConfiguration {
        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk()

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk()

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
