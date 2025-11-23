package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class JpaOutboxPartitionAssignmentRepositoryTest {
    @Autowired
    private lateinit var partitionAssignmentRepository: PartitionAssignmentRepository

    @Autowired
    private lateinit var testEntityManager: TestEntityManager

    @Test
    fun `claimAllPartitions creates all partitions and assigns to instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")

        val all = partitionAssignmentRepository.findAll()

        assertThat(all).hasSize(TOTAL_PARTITIONS)
        assertThat(all.map { it.instanceId }).allMatch { it == "instance-1" }
        assertThat(all.map { it.partitionNumber }).containsExactlyElementsOf(0 until TOTAL_PARTITIONS)
    }

    @Test
    fun `claimAllPartitions is idempotent`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val firstCount = partitionAssignmentRepository.findAll().size

        testEntityManager.flush()
        testEntityManager.clear()

        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val secondCount = partitionAssignmentRepository.findAll().size

        assertThat(firstCount).isEqualTo(secondCount).isEqualTo(TOTAL_PARTITIONS)
    }

    @Test
    fun `findAll returns all partitions ordered by partition number`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")

        val all = partitionAssignmentRepository.findAll()

        assertThat(all.map { it.partitionNumber }).isSorted()
    }

    @Test
    fun `findAll returns empty when no partitions exist`() {
        val all = partitionAssignmentRepository.findAll()

        assertThat(all).isEmpty()
    }

    @Test
    fun `findByInstanceId returns partitions assigned to instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")

        val assigned = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(assigned).hasSize(TOTAL_PARTITIONS)
        assertThat(assigned.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `findByInstanceId returns empty for non-existent instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")

        val assigned = partitionAssignmentRepository.findByInstanceId("non-existent")

        assertThat(assigned).isEmpty()
    }

    @Test
    fun `findByInstanceId returns empty when all partitions unassigned`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        partitionAssignmentRepository.releasePartitions(all.map { it.partitionNumber }.toSet(), "instance-1")

        val assigned = partitionAssignmentRepository.findByInstanceId("instance-1")

        assertThat(assigned).isEmpty()
    }

    @Test
    fun `releasePartitions unassigns partitions from instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        val toRelease = all.take(10).map { it.partitionNumber }.toSet()

        partitionAssignmentRepository.releasePartitions(toRelease, "instance-1")

        testEntityManager.flush()
        testEntityManager.clear()

        val remaining = partitionAssignmentRepository.findByInstanceId("instance-1")
        assertThat(remaining.map { it.partitionNumber }).doesNotContainAnyElementsOf(toRelease)
        val released = partitionAssignmentRepository.findAll().filter { toRelease.contains(it.partitionNumber) }
        assertThat(released.map { it.instanceId }).allMatch { it == null }
    }

    @Test
    fun `releasePartitions does nothing when empty set provided`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val beforeCount = partitionAssignmentRepository.findByInstanceId("instance-1").size

        partitionAssignmentRepository.releasePartitions(emptySet(), "instance-1")

        val afterCount = partitionAssignmentRepository.findByInstanceId("instance-1").size
        assertThat(afterCount).isEqualTo(beforeCount)
    }

    @Test
    fun `releasePartitions throws when partitions not owned by instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        val toRelease = all.take(10).map { it.partitionNumber }.toSet()

        assertThatThrownBy {
            partitionAssignmentRepository.releasePartitions(toRelease, "instance-2")
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `claimStalePartitions reassigns partitions to new instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        val toClaim = all.take(10).map { it.partitionNumber }.toSet()
        partitionAssignmentRepository.releasePartitions(toClaim, "instance-1")

        testEntityManager.flush()
        testEntityManager.clear()

        partitionAssignmentRepository.claimStalePartitions(toClaim, setOf("instance-1"), "instance-2")

        val claimed = partitionAssignmentRepository.findByInstanceId("instance-2")
        assertThat(claimed.map { it.partitionNumber }).containsAll(toClaim)
    }

    @Test
    fun `claimStalePartitions claims unassigned partitions`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        val toClaim = all.take(10).map { it.partitionNumber }.toSet()
        partitionAssignmentRepository.releasePartitions(toClaim, "instance-1")

        testEntityManager.flush()
        testEntityManager.clear()

        partitionAssignmentRepository.claimStalePartitions(toClaim, null, "instance-2")

        val claimed = partitionAssignmentRepository.findByInstanceId("instance-2")
        assertThat(claimed.map { it.partitionNumber }).containsAll(toClaim)
    }

    @Test
    fun `claimStalePartitions throws when partition owned by different instance`() {
        partitionAssignmentRepository.claimAllPartitions("instance-1")
        val all = partitionAssignmentRepository.findAll()
        val toClaim = all.take(10).map { it.partitionNumber }.toSet()

        assertThatThrownBy {
            partitionAssignmentRepository.claimStalePartitions(toClaim, setOf("instance-2"), "instance-3")
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `claimStalePartitions throws when partition missing`() {
        val nonExistentPartitions = setOf(9999, 9998)

        assertThatThrownBy {
            partitionAssignmentRepository.claimStalePartitions(nonExistentPartitions, null, "instance-1")
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
