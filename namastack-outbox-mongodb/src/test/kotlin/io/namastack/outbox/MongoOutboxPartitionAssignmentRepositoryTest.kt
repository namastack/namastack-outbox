package io.namastack.outbox

import com.mongodb.client.MongoClients
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.transaction.support.TransactionTemplate

@Testcontainers
class MongoOutboxPartitionAssignmentRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongodb = MongoDBContainer("mongo:8.0")
    }

    private val clock = Clock.systemUTC()
    private lateinit var mongoTemplate: MongoTemplate
    private lateinit var repository: MongoOutboxPartitionAssignmentRepository

    @BeforeEach
    fun setUp() {
        val client = MongoClients.create(mongodb.connectionString)
        val dbFactory = SimpleMongoClientDatabaseFactory(client, "testdb")
        mongoTemplate = MongoTemplate(dbFactory)
        
        val transactionManager = MongoTransactionManager(dbFactory)
        val transactionTemplate = TransactionTemplate(transactionManager)
        
        repository = MongoOutboxPartitionAssignmentRepository(mongoTemplate, transactionTemplate)
        mongoTemplate.dropCollection(MongoOutboxPartitionAssignmentEntity::class.java)
    }

    @Test
    fun `findAll returns empty when no partitions exist`() {
        val all = repository.findAll()

        assertThat(all).isEmpty()
    }

    @Test
    fun `findAll returns all partitions ordered by partition number`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        repository.saveAll(assignments)

        val all = repository.findAll()

        assertThat(all).hasSize(TOTAL_PARTITIONS)
        assertThat(all.map { it.partitionNumber }).isSorted()
        assertThat(all.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `findByInstanceId returns empty when no partitions assigned`() {
        val assigned = repository.findByInstanceId("instance-1")

        assertThat(assigned).isEmpty()
    }

    @Test
    fun `findByInstanceId returns only partitions assigned to instance`() {
        val i1Assignments = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        val i2Assignments = (100..199).map { PartitionAssignment.create(it, "instance-2", clock, null) }.toSet()
        val allAssignments = i1Assignments + i2Assignments

        repository.saveAll(allAssignments)

        val i1Partitions = repository.findByInstanceId("instance-1")
        val i2Partitions = repository.findByInstanceId("instance-2")

        assertThat(i1Partitions).hasSize(100)
        assertThat(i1Partitions.map { it.instanceId }).allMatch { it == "instance-1" }
        assertThat(i2Partitions).hasSize(100)
        assertThat(i2Partitions.map { it.instanceId }).allMatch { it == "instance-2" }
    }

    @Test
    fun `findByInstanceId returns empty for non-existent instance`() {
        val assignments = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        repository.saveAll(assignments)

        val nonExistent = repository.findByInstanceId("non-existent")

        assertThat(nonExistent).isEmpty()
    }

    @Test
    fun `saveAll creates new partitions`() {
        val assignments = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        repository.saveAll(assignments)

        val saved = repository.findAll()

        assertThat(saved).hasSize(10)
        assertThat(saved.map { it.instanceId }).allMatch { it == "instance-1" }
    }

    @Test
    fun `saveAll updates existing partitions`() {
        val initial = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        repository.saveAll(initial)

        // Re-fetch to get versions, then claim for instance-2
        val existing = repository.findByInstanceId("instance-1")
        val updated = existing.map { assignment ->
            assignment.claim("instance-2", clock)
            assignment
        }.toSet()

        repository.saveAll(updated)

        val result = repository.findByInstanceId("instance-2")

        assertThat(result).hasSize(10)
        assertThat(result.map { it.partitionNumber }).containsExactlyElementsOf(0..9)
    }

    @Test
    fun `saveAll with unassigned partitions persists null instanceId`() {
        val assigned = (0..99).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        val unassigned = (100..109).map { PartitionAssignment(it, null, Instant.now(clock)) }.toSet()
        val all = assigned + unassigned

        repository.saveAll(all)

        val findAll = repository.findAll()
        val findByInstance = repository.findByInstanceId("instance-1")

        assertThat(findAll).hasSize(110)
        assertThat(findByInstance).hasSize(100)
        val unassignedFromDb = findAll.filter { it.instanceId == null }
        assertThat(unassignedFromDb).hasSize(10)
    }

    @Test
    fun `saveAll with mixed operations persists all changes`() {
        val initial = (0..19).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        repository.saveAll(initial)

        val allAssignments = repository.findAll().toMutableSet()

        val toRelease = allAssignments.filter { it.partitionNumber in 0..4 }.toSet()
        val toClaim = allAssignments.filter { it.partitionNumber in 5..9 }.toSet()
        val toKeep = allAssignments.filter { it.partitionNumber in 10..19 }.toSet()

        val released = toRelease.map { it.copy(instanceId = null) }.toSet()
        val claimed = toClaim.map { it.copy(instanceId = "instance-2") }.toSet()
        val updated = released + claimed + toKeep

        repository.saveAll(updated)

        val i1Final = repository.findByInstanceId("instance-1")
        val i2Final = repository.findByInstanceId("instance-2")
        val unassignedFinal = repository.findAll().filter { it.instanceId == null }

        assertThat(i1Final.map { it.partitionNumber }).containsExactlyElementsOf(10..19)
        assertThat(i2Final.map { it.partitionNumber }).containsExactlyElementsOf(5..9)
        assertThat(unassignedFinal.map { it.partitionNumber }).containsExactlyElementsOf(0..4)
    }

    @Test
    fun `saveAll with empty set does nothing`() {
        val initial = (0..9).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()
        repository.saveAll(initial)

        val beforeCount = repository.findAll().size

        repository.saveAll(emptySet())

        val afterCount = repository.findAll().size

        assertThat(afterCount).isEqualTo(beforeCount)
    }

    @Test
    fun `saveAll persists all 256 partitions`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "instance-1", clock, null) }.toSet()

        repository.saveAll(assignments)

        val all = repository.findAll()
        val byInstance = repository.findByInstanceId("instance-1")

        assertThat(all).hasSize(TOTAL_PARTITIONS)
        assertThat(byInstance).hasSize(TOTAL_PARTITIONS)
        assertThat(all.map { it.partitionNumber }).containsExactlyElementsOf(0..255)
    }
}
