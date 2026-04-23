package io.namastack.outbox

import com.mongodb.client.MongoClients
import io.namastack.outbox.config.MongoOutboxConfigurationProperties
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.DEAD
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.dropCollection
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.MINUTES

@Testcontainers
class MongoOutboxInstanceRepositoryTest {
    companion object {
        @JvmStatic
        val mongodb: MongoDBContainer =
            MongoDBContainer("mongo:8.0")
                .withReuse(true)
                .apply { start() }
    }

    private val clock: Clock = Clock.systemDefaultZone()
    private lateinit var mongoTemplate: MongoTemplate
    private lateinit var repository: MongoOutboxInstanceRepository

    @BeforeEach
    fun setUp() {
        val client = MongoClients.create(mongodb.connectionString)
        mongoTemplate = MongoTemplate(client, "testdb")
        repository =
            MongoOutboxInstanceRepository(
                mongoTemplate,
                MongoCollectionNameResolver(MongoOutboxConfigurationProperties()),
            )
        mongoTemplate.dropCollection<MongoOutboxInstanceEntity>()
    }

    @Test
    fun `saves an instance`() {
        val instance = createInstance("instance-1", ACTIVE)

        repository.save(instance)

        val persistedInstance = repository.findById("instance-1")!!

        assertThat(persistedInstance.instanceId).isEqualTo(instance.instanceId)
        assertThat(persistedInstance.hostname).isEqualTo(instance.hostname)
        assertThat(persistedInstance.port).isEqualTo(instance.port)
        assertThat(persistedInstance.status).isEqualTo(instance.status)
        assertThat(persistedInstance.startedAt).isCloseTo(instance.startedAt, within(1, ChronoUnit.MILLIS))
        assertThat(persistedInstance.lastHeartbeat).isCloseTo(instance.lastHeartbeat, within(1, ChronoUnit.MILLIS))
        assertThat(persistedInstance.createdAt).isCloseTo(instance.createdAt, within(1, ChronoUnit.MILLIS))
        assertThat(persistedInstance.updatedAt).isCloseTo(instance.updatedAt, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `updates an existing instance`() {
        val instance = createInstance("instance-1", ACTIVE)
        repository.save(instance)

        val updatedInstance = instance.copy(status = SHUTTING_DOWN)
        repository.save(updatedInstance)

        val persistedInstance = repository.findById("instance-1")!!
        assertThat(persistedInstance.status).isEqualTo(SHUTTING_DOWN)
    }

    @Test
    fun `finds instance by id`() {
        val instance = createInstance("instance-1", ACTIVE)
        repository.save(instance)

        val foundInstance = repository.findById("instance-1")

        assertThat(foundInstance).isNotNull
        assertThat(foundInstance!!.instanceId).isEqualTo("instance-1")
    }

    @Test
    fun `returns null when instance not found`() {
        val foundInstance = repository.findById("non-existent")

        assertThat(foundInstance).isNull()
    }

    @Test
    fun `finds all instances ordered by created date`() {
        val now = Instant.now(clock)
        val instance1 = createInstanceAtTime("instance-1", ACTIVE, now.minus(2, MINUTES))
        val instance2 = createInstanceAtTime("instance-2", ACTIVE, now.minus(1, MINUTES))
        val instance3 = createInstanceAtTime("instance-3", SHUTTING_DOWN, now)

        repository.save(instance1)
        repository.save(instance2)
        repository.save(instance3)

        val instances = repository.findAll()

        assertThat(instances).hasSize(3)
        assertThat(instances.map { it.instanceId }).containsExactly("instance-1", "instance-2", "instance-3")
    }

    @Test
    fun `finds active instances`() {
        repository.save(createInstance("active-1", ACTIVE))
        repository.save(createInstance("active-2", ACTIVE))
        repository.save(createInstance("shutting-down", SHUTTING_DOWN))
        repository.save(createInstance("dead", DEAD))

        val activeInstances = repository.findByStatus(ACTIVE)

        assertThat(activeInstances).hasSize(2)
        assertThat(activeInstances.map { it.instanceId }).containsExactlyInAnyOrder("active-1", "active-2")
    }

    @Test
    fun `finds active instances using dedicated method`() {
        repository.save(createInstance("active-1", ACTIVE))
        repository.save(createInstance("active-2", ACTIVE))
        repository.save(createInstance("shutting-down", SHUTTING_DOWN))
        repository.save(createInstance("dead", DEAD))

        val activeInstances = repository.findActiveInstances()

        assertThat(activeInstances).hasSize(2)
        assertThat(activeInstances.map { it.instanceId }).containsExactlyInAnyOrder("active-1", "active-2")
    }

    @Test
    fun `finds instances with stale heartbeat`() {
        val cutoffTime = Instant.now(clock)
        val staleActive = createInstanceWithHeartbeat("stale-active", ACTIVE, cutoffTime.minus(1, MINUTES))
        val staleShuttingDown =
            createInstanceWithHeartbeat("stale-shutting", SHUTTING_DOWN, cutoffTime.minus(2, MINUTES))
        val recentActive = createInstanceWithHeartbeat("recent-active", ACTIVE, cutoffTime.plus(1, MINUTES))
        val staleDead = createInstanceWithHeartbeat("stale-dead", DEAD, cutoffTime.minus(3, MINUTES))

        repository.save(staleActive)
        repository.save(staleShuttingDown)
        repository.save(recentActive)
        repository.save(staleDead)

        val staleInstances = repository.findInstancesWithStaleHeartbeat(cutoffTime)

        assertThat(staleInstances).hasSize(2)
        assertThat(staleInstances.map { it.instanceId }).containsExactlyInAnyOrder("stale-active", "stale-shutting")
    }

    @Test
    fun `finds instances with stale heartbeat returns empty when none exist`() {
        val cutoffTime = Instant.now(clock)
        val recentInstance = createInstanceWithHeartbeat("recent", ACTIVE, cutoffTime.plus(1, MINUTES))

        repository.save(recentInstance)

        val staleInstances = repository.findInstancesWithStaleHeartbeat(cutoffTime)

        assertThat(staleInstances).isEmpty()
    }

    @Test
    fun `counts instances by status`() {
        repository.save(createInstance("active-1", ACTIVE))
        repository.save(createInstance("active-2", ACTIVE))
        repository.save(createInstance("shutting-down", SHUTTING_DOWN))
        repository.save(createInstance("dead", DEAD))

        val activeCount = repository.countByStatus(ACTIVE)
        val shuttingDownCount = repository.countByStatus(SHUTTING_DOWN)
        val deadCount = repository.countByStatus(DEAD)

        assertThat(activeCount).isEqualTo(2)
        assertThat(shuttingDownCount).isEqualTo(1)
        assertThat(deadCount).isEqualTo(1)
    }

    @Test
    fun `counts instances by status returns zero when none match`() {
        repository.save(createInstance("active", ACTIVE))

        val deadCount = repository.countByStatus(DEAD)

        assertThat(deadCount).isEqualTo(0)
    }

    @Test
    fun `updates heartbeat`() {
        val instance = createInstance("instance-1", ACTIVE)
        repository.save(instance)

        val newHeartbeat = Instant.now(clock).plus(1, MINUTES)
        val result = repository.updateHeartbeat("instance-1", newHeartbeat)

        assertThat(result).isTrue()

        val updatedInstance = repository.findById("instance-1")!!
        assertThat(updatedInstance.lastHeartbeat).isCloseTo(newHeartbeat, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `heartbeat update returns false for non-existent instance`() {
        val newHeartbeat = Instant.now(clock)
        val result = repository.updateHeartbeat("non-existent", newHeartbeat)

        assertThat(result).isFalse()
    }

    @Test
    fun `updates status`() {
        val instance = createInstance("instance-1", ACTIVE)
        repository.save(instance)

        val newTimestamp = Instant.now(clock).plus(1, MINUTES)
        val result = repository.updateStatus("instance-1", SHUTTING_DOWN, newTimestamp)

        assertThat(result).isTrue()

        val updatedInstance = repository.findById("instance-1")!!
        assertThat(updatedInstance.status).isEqualTo(SHUTTING_DOWN)
        assertThat(updatedInstance.updatedAt).isCloseTo(newTimestamp, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `status update returns false for non-existent instance`() {
        val newTimestamp = Instant.now(clock)
        val result = repository.updateStatus("non-existent", SHUTTING_DOWN, newTimestamp)

        assertThat(result).isFalse()
    }

    @Test
    fun `deletes instance by id`() {
        val instance = createInstance("instance-1", ACTIVE)
        repository.save(instance)

        val result = repository.deleteById("instance-1")

        assertThat(result).isTrue()
        assertThat(repository.findById("instance-1")).isNull()
    }

    @Test
    fun `delete by id returns false for non-existent instance`() {
        val result = repository.deleteById("non-existent")

        assertThat(result).isFalse()
    }

    // ==================== Helper methods ====================

    private fun createInstance(
        instanceId: String,
        status: OutboxInstanceStatus,
    ): OutboxInstance =
        OutboxInstance.create(
            instanceId = instanceId,
            hostname = "localhost",
            port = 8080,
            status = status,
            clock = clock,
        )

    private fun createInstanceAtTime(
        instanceId: String,
        status: OutboxInstanceStatus,
        createdAt: Instant,
    ): OutboxInstance =
        OutboxInstance(
            instanceId = instanceId,
            hostname = "localhost",
            port = 8080,
            status = status,
            startedAt = createdAt,
            lastHeartbeat = createdAt,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun createInstanceWithHeartbeat(
        instanceId: String,
        status: OutboxInstanceStatus,
        lastHeartbeat: Instant,
    ): OutboxInstance {
        val now = Instant.now(clock)
        return OutboxInstance(
            instanceId = instanceId,
            hostname = "localhost",
            port = 8080,
            status = status,
            startedAt = now,
            lastHeartbeat = lastHeartbeat,
            createdAt = now,
            updatedAt = now,
        )
    }
}
