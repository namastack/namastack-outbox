package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.OffsetDateTime

@JdbcTest
@ImportAutoConfiguration(JdbcOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
class JdbcOutboxPartitionAssignmentRepositoryTest {
    @Autowired
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val clock = Clock.systemUTC()

    @Test
    fun `findAll returns empty when no partitions exist`() {
        val all = partitionAssignmentRepository.findAll()

        assertThat(all).isEmpty()
    }

    @Test
    fun `findAll returns all partitions ordered by partition number`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        partitionAssignmentRepository.saveAll(assignments)

        val all = partitionAssignmentRepository.findAll()

        assertThat(all).hasSize(TOTAL_PARTITIONS)
        assertThat(all.map { it.partitionNumber }).isSorted()
        assertThat(all.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `findByInstanceId returns empty when no partitions assigned`() {
        val assigned = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(assigned).isEmpty()
    }

    @Test
    fun `findByInstanceId returns only partitions assigned to instance`() {
        val i1Assignments = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        val i2Assignments = (100..199).map { PartitionAssignment.create(it, "instance-2", clock, null) }.toSet()
        val allAssignments = i1Assignments + i2Assignments

        partitionAssignmentRepository.saveAll(allAssignments)

        val i1Partitions = partitionAssignmentRepository.findByInstanceId("instance-1")
        val i2Partitions = partitionAssignmentRepository.findByInstanceId("instance-2")

        assertThat(i1Partitions).hasSize(100)
        assertThat(i1Partitions.map { it.instanceId }).allMatch { it == "instance-1" }
        assertThat(i2Partitions).hasSize(100)
        assertThat(i2Partitions.map { it.instanceId }).allMatch { it == "instance-2" }
    }

    @Test
    fun `findByInstanceId returns empty for non-existent instance`() {
        val assignments = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        partitionAssignmentRepository.saveAll(assignments)

        val nonExistent = partitionAssignmentRepository.findByInstanceId("non-existent")

        assertThat(nonExistent).isEmpty()
    }

    @Test
    fun `saveAll creates new partitions`() {
        val assignments = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        partitionAssignmentRepository.saveAll(assignments)

        val saved = partitionAssignmentRepository.findAll()

        assertThat(saved).hasSize(10)
        assertThat(saved.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `saveAll updates existing partitions`() {
        val initial = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        partitionAssignmentRepository.saveAll(initial)

        val updated =
            (0..9)
                .map {
                    val existing =
                        partitionAssignmentRepository.findByInstanceId("instance-1").first { a ->
                            a.partitionNumber == it
                        }
                    existing.claim("instance-2", clock)
                    existing
                }.toSet()

        partitionAssignmentRepository.saveAll(updated)

        val result = partitionAssignmentRepository.findByInstanceId("instance-2")

        assertThat(result).hasSize(10)
        assertThat(result.map { it.partitionNumber }).containsExactlyElementsOf(0..9)
    }

    @Test
    fun `saveAll with unassigned partitions persists null instanceId`() {
        val assigned = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        val unassigned = (100..109).map { PartitionAssignment(it, null, OffsetDateTime.now(clock)) }.toSet()
        val all = assigned + unassigned

        partitionAssignmentRepository.saveAll(all)

        val findAll = partitionAssignmentRepository.findAll()
        val findByInstance = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(findAll).hasSize(110)
        assertThat(findByInstance).hasSize(100)
        val unassignedFromDb = findAll.filter { it.instanceId == null }
        assertThat(unassignedFromDb).hasSize(10)
    }

    @Test
    fun `saveAll throws OptimisticLockingFailureException on version mismatch`() {
        // Create initial partition
        val initial = PartitionAssignment.create(0, "instance-1", clock, null)
        partitionAssignmentRepository.saveAll(setOf(initial))

        // Load the same partition (simulating two concurrent instances)
        val loaded1 = partitionAssignmentRepository.findAll().first { it.partitionNumber == 0 }
        val loaded2 = partitionAssignmentRepository.findAll().first { it.partitionNumber == 0 }

        // First instance claims the partition successfully
        loaded1.claim("instance-2", clock)
        partitionAssignmentRepository.saveAll(setOf(loaded1))

        // Second instance tries to claim the same partition with old version -> should fail
        loaded2.claim("instance-3", clock)

        assertThatThrownBy {
            partitionAssignmentRepository.saveAll(setOf(loaded2))
        }.isInstanceOf(OptimisticLockingFailureException::class.java)
            .hasMessageContaining("version mismatch")
    }

    @Test
    fun `version is incremented on update`() {
        val assignment = PartitionAssignment.create(0, "instance-1", clock, null)
        partitionAssignmentRepository.saveAll(setOf(assignment))

        val initialVersion = partitionAssignmentRepository.findAll().first().version

        val loaded = partitionAssignmentRepository.findAll().first()
        loaded.claim("instance-2", clock)
        partitionAssignmentRepository.saveAll(setOf(loaded))

        val updatedVersion = partitionAssignmentRepository.findAll().first().version

        assertThat(initialVersion).isEqualTo(0)
        assertThat(updatedVersion).isEqualTo(1)
    }

    @Test
    fun `saveAll handles release correctly`() {
        val assignment = PartitionAssignment.create(0, "instance-1", clock, null)
        partitionAssignmentRepository.saveAll(setOf(assignment))

        val loaded = partitionAssignmentRepository.findAll().first()
        loaded.release("instance-1", clock)
        partitionAssignmentRepository.saveAll(setOf(loaded))

        val released = partitionAssignmentRepository.findAll().first()

        assertThat(released.instanceId).isNull()
        assertThat(released.version).isEqualTo(1)
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
