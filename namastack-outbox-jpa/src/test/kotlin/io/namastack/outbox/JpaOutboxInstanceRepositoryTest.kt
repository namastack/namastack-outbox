package io.namastack.outbox

import io.namastack.outbox.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.OutboxInstanceStatus.DEAD
import io.namastack.outbox.OutboxInstanceStatus.SHUTTING_DOWN
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class JpaOutboxInstanceRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var jpaOutboxInstanceRepository: JpaOutboxInstanceRepository

    @Autowired
    private lateinit var testEntityManager: TestEntityManager

    @Test
    fun `saves an instance`() {
        val instance = createInstance("instance-1", ACTIVE)

        jpaOutboxInstanceRepository.save(instance)

        val persistedInstance = jpaOutboxInstanceRepository.findById("instance-1")!!

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
        jpaOutboxInstanceRepository.save(instance)

        val updatedInstance = instance.copy(status = SHUTTING_DOWN)
        jpaOutboxInstanceRepository.save(updatedInstance)

        val persistedInstance = jpaOutboxInstanceRepository.findById("instance-1")!!
        assertThat(persistedInstance.status).isEqualTo(SHUTTING_DOWN)
    }

    @Test
    fun `finds instance by id`() {
        val instance = createInstance("instance-1", ACTIVE)
        jpaOutboxInstanceRepository.save(instance)

        val foundInstance = jpaOutboxInstanceRepository.findById("instance-1")

        assertThat(foundInstance).isNotNull
        assertThat(foundInstance!!.instanceId).isEqualTo("instance-1")
    }

    @Test
    fun `returns null when instance not found`() {
        val foundInstance = jpaOutboxInstanceRepository.findById("non-existent")

        assertThat(foundInstance).isNull()
    }

    @Test
    fun `finds all instances ordered by created date`() {
        val now = OffsetDateTime.now(clock)
        val instance1 = createInstanceAtTime("instance-1", ACTIVE, now.minusMinutes(2))
        val instance2 = createInstanceAtTime("instance-2", ACTIVE, now.minusMinutes(1))
        val instance3 = createInstanceAtTime("instance-3", SHUTTING_DOWN, now)

        jpaOutboxInstanceRepository.save(instance1)
        jpaOutboxInstanceRepository.save(instance2)
        jpaOutboxInstanceRepository.save(instance3)

        val instances = jpaOutboxInstanceRepository.findAll()

        assertThat(instances).hasSize(3)
        assertThat(instances.map { it.instanceId }).containsExactly("instance-1", "instance-2", "instance-3")
    }

    @Test
    fun `finds active instances`() {
        jpaOutboxInstanceRepository.save(createInstance("active-1", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("active-2", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("shutting-down", SHUTTING_DOWN))
        jpaOutboxInstanceRepository.save(createInstance("dead", DEAD))

        val activeInstances = jpaOutboxInstanceRepository.findByStatus(ACTIVE)

        assertThat(activeInstances).hasSize(2)
        assertThat(activeInstances.map { it.instanceId }).containsExactlyInAnyOrder("active-1", "active-2")
    }

    @Test
    fun `finds active instances using dedicated method`() {
        jpaOutboxInstanceRepository.save(createInstance("active-1", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("active-2", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("shutting-down", SHUTTING_DOWN))
        jpaOutboxInstanceRepository.save(createInstance("dead", DEAD))

        val activeInstances = jpaOutboxInstanceRepository.findActiveInstances()

        assertThat(activeInstances).hasSize(2)
        assertThat(activeInstances.map { it.instanceId }).containsExactlyInAnyOrder("active-1", "active-2")
    }

    @Test
    fun `finds instances with stale heartbeat`() {
        val cutoffTime = OffsetDateTime.now(clock)
        val staleActive = createInstanceWithHeartbeat("stale-active", ACTIVE, cutoffTime.minusMinutes(1))
        val staleShuttingDown = createInstanceWithHeartbeat("stale-shutting", SHUTTING_DOWN, cutoffTime.minusMinutes(2))
        val recentActive = createInstanceWithHeartbeat("recent-active", ACTIVE, cutoffTime.plusMinutes(1))
        val staleDead = createInstanceWithHeartbeat("stale-dead", DEAD, cutoffTime.minusMinutes(3))

        jpaOutboxInstanceRepository.save(staleActive)
        jpaOutboxInstanceRepository.save(staleShuttingDown)
        jpaOutboxInstanceRepository.save(recentActive)
        jpaOutboxInstanceRepository.save(staleDead)

        val staleInstances = jpaOutboxInstanceRepository.findInstancesWithStaleHeartbeat(cutoffTime)

        assertThat(staleInstances).hasSize(2)
        assertThat(staleInstances.map { it.instanceId }).containsExactlyInAnyOrder("stale-active", "stale-shutting")
    }

    @Test
    fun `finds instances with stale heartbeat returns empty when none exist`() {
        val cutoffTime = OffsetDateTime.now(clock)
        val recentInstance = createInstanceWithHeartbeat("recent", ACTIVE, cutoffTime.plusMinutes(1))

        jpaOutboxInstanceRepository.save(recentInstance)

        val staleInstances = jpaOutboxInstanceRepository.findInstancesWithStaleHeartbeat(cutoffTime)

        assertThat(staleInstances).isEmpty()
    }

    @Test
    fun `counts all instances`() {
        jpaOutboxInstanceRepository.save(createInstance("instance-1", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("instance-2", SHUTTING_DOWN))
        jpaOutboxInstanceRepository.save(createInstance("instance-3", DEAD))

        val count = jpaOutboxInstanceRepository.count()

        assertThat(count).isEqualTo(3)
    }

    @Test
    fun `counts instances returns zero when empty`() {
        val count = jpaOutboxInstanceRepository.count()

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `counts instances by status`() {
        jpaOutboxInstanceRepository.save(createInstance("active-1", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("active-2", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("shutting-down", SHUTTING_DOWN))
        jpaOutboxInstanceRepository.save(createInstance("dead", DEAD))

        val activeCount = jpaOutboxInstanceRepository.countByStatus(ACTIVE)
        val shuttingDownCount = jpaOutboxInstanceRepository.countByStatus(SHUTTING_DOWN)
        val deadCount = jpaOutboxInstanceRepository.countByStatus(DEAD)

        assertThat(activeCount).isEqualTo(2)
        assertThat(shuttingDownCount).isEqualTo(1)
        assertThat(deadCount).isEqualTo(1)
    }

    @Test
    fun `counts instances by status returns zero when none match`() {
        jpaOutboxInstanceRepository.save(createInstance("active", ACTIVE))

        val deadCount = jpaOutboxInstanceRepository.countByStatus(DEAD)

        assertThat(deadCount).isEqualTo(0)
    }

    @Test
    fun `updates heartbeat`() {
        val instance = createInstance("instance-1", ACTIVE)
        jpaOutboxInstanceRepository.save(instance)

        val newHeartbeat = OffsetDateTime.now(clock).plusMinutes(1)
        val result = jpaOutboxInstanceRepository.updateHeartbeat("instance-1", newHeartbeat)

        assertThat(result).isTrue()

        val updatedInstance = jpaOutboxInstanceRepository.findById("instance-1")!!
        assertThat(updatedInstance.lastHeartbeat).isCloseTo(newHeartbeat, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `heartbeat update returns false for non-existent instance`() {
        val newHeartbeat = OffsetDateTime.now(clock)
        val result = jpaOutboxInstanceRepository.updateHeartbeat("non-existent", newHeartbeat)

        assertThat(result).isFalse()
    }

    @Test
    fun `updates status`() {
        val instance = createInstance("instance-1", ACTIVE)
        jpaOutboxInstanceRepository.save(instance)

        val newTimestamp = OffsetDateTime.now(clock).plusMinutes(1)
        val result = jpaOutboxInstanceRepository.updateStatus("instance-1", SHUTTING_DOWN, newTimestamp)

        assertThat(result).isTrue()

        val updatedInstance = jpaOutboxInstanceRepository.findById("instance-1")!!
        assertThat(updatedInstance.status).isEqualTo(SHUTTING_DOWN)
        assertThat(updatedInstance.updatedAt).isCloseTo(newTimestamp, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `status update returns false for non-existent instance`() {
        val newTimestamp = OffsetDateTime.now(clock)
        val result = jpaOutboxInstanceRepository.updateStatus("non-existent", SHUTTING_DOWN, newTimestamp)

        assertThat(result).isFalse()
    }

    @Test
    fun `deletes instance by id`() {
        val instance = createInstance("instance-1", ACTIVE)
        jpaOutboxInstanceRepository.save(instance)

        val result = jpaOutboxInstanceRepository.deleteById("instance-1")

        assertThat(result).isTrue()
        assertThat(jpaOutboxInstanceRepository.findById("instance-1")).isNull()
    }

    @Test
    fun `delete by id returns false for non-existent instance`() {
        val result = jpaOutboxInstanceRepository.deleteById("non-existent")

        assertThat(result).isFalse()
    }

    @Test
    fun `deletes instances by status`() {
        jpaOutboxInstanceRepository.save(createInstance("active-1", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("active-2", ACTIVE))
        jpaOutboxInstanceRepository.save(createInstance("dead", DEAD))

        val deletedCount = jpaOutboxInstanceRepository.deleteByStatus(ACTIVE)

        assertThat(deletedCount).isEqualTo(2)
        assertThat(jpaOutboxInstanceRepository.findByStatus(ACTIVE)).isEmpty()
        assertThat(jpaOutboxInstanceRepository.findByStatus(DEAD)).hasSize(1)
    }

    @Test
    fun `deletes stale instances`() {
        val cutoffTime = OffsetDateTime.now(clock)
        val staleInstance1 = createInstanceWithHeartbeat("stale-1", ACTIVE, cutoffTime.minusMinutes(1))
        val staleInstance2 = createInstanceWithHeartbeat("stale-2", ACTIVE, cutoffTime.minusMinutes(2))
        val activeInstance = createInstanceWithHeartbeat("active", ACTIVE, cutoffTime.plusMinutes(1))

        jpaOutboxInstanceRepository.save(staleInstance1)
        jpaOutboxInstanceRepository.save(staleInstance2)
        jpaOutboxInstanceRepository.save(activeInstance)

        testEntityManager.flush()
        testEntityManager.clear()

        val deletedCount = jpaOutboxInstanceRepository.deleteStaleInstances(cutoffTime)

        testEntityManager.flush()
        testEntityManager.clear()

        assertThat(deletedCount).isEqualTo(2)
        assertThat(jpaOutboxInstanceRepository.findById("stale-1")).isNull()
        assertThat(jpaOutboxInstanceRepository.findById("stale-2")).isNull()
        assertThat(jpaOutboxInstanceRepository.findById("active")).isNotNull()
    }

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
        createdAt: OffsetDateTime,
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
        lastHeartbeat: OffsetDateTime,
    ): OutboxInstance {
        val now = OffsetDateTime.now(clock)
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

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
