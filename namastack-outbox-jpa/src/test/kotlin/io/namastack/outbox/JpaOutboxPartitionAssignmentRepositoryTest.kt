package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.config.JpaOutboxAutoConfiguration
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Clock
import java.time.OffsetDateTime

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
class JpaOutboxPartitionAssignmentRepositoryTest {
    @Autowired
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    @Autowired
    private lateinit var testEntityManager: TestEntityManager

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
        testEntityManager.flush()
        testEntityManager.clear()

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
        testEntityManager.flush()
        testEntityManager.clear()

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
        testEntityManager.flush()
        testEntityManager.clear()

        val nonExistent = partitionAssignmentRepository.findByInstanceId("non-existent")

        assertThat(nonExistent).isEmpty()
    }

    @Test
    fun `saveAll creates new partitions`() {
        val assignments = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        partitionAssignmentRepository.saveAll(assignments)
        testEntityManager.flush()
        testEntityManager.clear()

        val saved = partitionAssignmentRepository.findAll()

        assertThat(saved).hasSize(10)
        assertThat(saved.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `saveAll updates existing partitions`() {
        val initial = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        partitionAssignmentRepository.saveAll(initial)
        testEntityManager.flush()
        testEntityManager.clear()

        val updated =
            (0..9)
                .map {
                    val existing =
                        partitionAssignmentRepository.findByInstanceId("instance-1").first { a ->
                            a.partitionNumber ==
                                it
                        }
                    existing.claim("instance-2", clock)
                    existing
                }.toSet()

        partitionAssignmentRepository.saveAll(updated)
        testEntityManager.flush()
        testEntityManager.clear()

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
        testEntityManager.flush()
        testEntityManager.clear()

        val findAll = partitionAssignmentRepository.findAll()
        val findByInstance = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(findAll).hasSize(110)
        assertThat(findByInstance).hasSize(100)
        val unassignedFromDb = findAll.filter { it.instanceId == null }
        assertThat(unassignedFromDb).hasSize(10)
    }

    @Test
    fun `saveAll with mixed operations persists all changes`() {
        val initial = (0..19).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        partitionAssignmentRepository.saveAll(initial)
        testEntityManager.flush()
        testEntityManager.clear()

        val allAssignments = partitionAssignmentRepository.findAll().toMutableSet()

        val toRelease = allAssignments.filter { it.partitionNumber in 0..4 }.toSet()
        val toClaim = allAssignments.filter { it.partitionNumber in 5..9 }.toSet()
        val toKeep = allAssignments.filter { it.partitionNumber in 10..19 }.toSet()

        val released = toRelease.map { it.copy(instanceId = null) }.toSet()
        val claimed = toClaim.map { it.copy(instanceId = "instance-2") }.toSet()
        val updated = released + claimed + toKeep

        partitionAssignmentRepository.saveAll(updated)
        testEntityManager.flush()
        testEntityManager.clear()

        val i1Final = partitionAssignmentRepository.findByInstanceId("instance-1")
        val i2Final = partitionAssignmentRepository.findByInstanceId("instance-2")
        val unassignedFinal = partitionAssignmentRepository.findAll().filter { it.instanceId == null }

        assertThat(i1Final.map { it.partitionNumber }).containsExactlyElementsOf(10..19)
        assertThat(i2Final.map { it.partitionNumber }).containsExactlyElementsOf(5..9)
        assertThat(unassignedFinal.map { it.partitionNumber }).containsExactlyElementsOf(0..4)
    }

    @Test
    fun `saveAll with empty set does nothing`() {
        val initial = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        partitionAssignmentRepository.saveAll(initial)
        testEntityManager.flush()
        testEntityManager.clear()

        val beforeCount = partitionAssignmentRepository.findAll().size

        partitionAssignmentRepository.saveAll(emptySet())
        testEntityManager.flush()
        testEntityManager.clear()

        val afterCount = partitionAssignmentRepository.findAll().size

        assertThat(afterCount).isEqualTo(beforeCount)
    }

    @Test
    fun `saveAll persists all 256 partitions`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        partitionAssignmentRepository.saveAll(assignments)
        testEntityManager.flush()
        testEntityManager.clear()

        val all = partitionAssignmentRepository.findAll()
        val byInstance = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(all).hasSize(TOTAL_PARTITIONS)
        assertThat(byInstance).hasSize(TOTAL_PARTITIONS)
        assertThat(all.map { it.partitionNumber }).containsExactlyElementsOf(0..255)
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
