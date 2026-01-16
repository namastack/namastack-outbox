package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.DEAD
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.MINUTES
import java.time.temporal.ChronoUnit.SECONDS

class OutboxInstanceEntityMapperTest {
    @Test
    fun `maps OutboxInstance to OutboxInstanceEntity with all properties`() {
        val now = Instant.now()
        val startedAt = now.minus(10, MINUTES)
        val lastHeartbeat = now.minus(1, MINUTES)
        val createdAt = now.minus(15, MINUTES)
        val updatedAt = now.minus(30, SECONDS)

        val instance =
            OutboxInstance(
                instanceId = "instance-123",
                hostname = "app-server-01",
                port = 8080,
                status = ACTIVE,
                startedAt = startedAt,
                lastHeartbeat = lastHeartbeat,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        val entity = OutboxInstanceEntityMapper.toEntity(instance)

        assertThat(entity.instanceId).isEqualTo("instance-123")
        assertThat(entity.hostname).isEqualTo("app-server-01")
        assertThat(entity.port).isEqualTo(8080)
        assertThat(entity.status).isEqualTo(ACTIVE)
        assertThat(entity.startedAt).isEqualTo(startedAt)
        assertThat(entity.lastHeartbeat).isEqualTo(lastHeartbeat)
        assertThat(entity.createdAt).isEqualTo(createdAt)
        assertThat(entity.updatedAt).isEqualTo(updatedAt)
    }

    @Test
    fun `maps OutboxInstanceEntity to OutboxInstance with all properties`() {
        val now = Instant.now()
        val startedAt = now.minus(5, MINUTES)
        val lastHeartbeat = now.minus(30, SECONDS)
        val createdAt = now.minus(10, MINUTES)
        val updatedAt = now.minus(15, SECONDS)

        val entity =
            OutboxInstanceEntity(
                instanceId = "instance-456",
                hostname = "worker-node-02",
                port = 9090,
                status = SHUTTING_DOWN,
                startedAt = startedAt,
                lastHeartbeat = lastHeartbeat,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        val instance = OutboxInstanceEntityMapper.fromEntity(entity)

        assertThat(instance.instanceId).isEqualTo("instance-456")
        assertThat(instance.hostname).isEqualTo("worker-node-02")
        assertThat(instance.port).isEqualTo(9090)
        assertThat(instance.status).isEqualTo(SHUTTING_DOWN)
        assertThat(instance.startedAt).isEqualTo(startedAt)
        assertThat(instance.lastHeartbeat).isEqualTo(lastHeartbeat)
        assertThat(instance.createdAt).isEqualTo(createdAt)
        assertThat(instance.updatedAt).isEqualTo(updatedAt)
    }

    @Test
    fun `maps OutboxInstance with DEAD status`() {
        val now = Instant.now()

        val instance =
            OutboxInstance(
                instanceId = "dead-instance",
                hostname = "failed-server",
                port = 7070,
                status = DEAD,
                startedAt = now.minus(1, ChronoUnit.HOURS),
                lastHeartbeat = now.minus(30, MINUTES),
                createdAt = now.minus(2, ChronoUnit.HOURS),
                updatedAt = now.minus(30, MINUTES),
            )

        val entity = OutboxInstanceEntityMapper.toEntity(instance)

        assertThat(entity.instanceId).isEqualTo("dead-instance")
        assertThat(entity.hostname).isEqualTo("failed-server")
        assertThat(entity.port).isEqualTo(7070)
        assertThat(entity.status).isEqualTo(DEAD)
    }

    @Test
    fun `maps OutboxInstance with different port numbers`() {
        val now = Instant.now()

        val instance =
            OutboxInstance(
                instanceId = "port-test",
                hostname = "localhost",
                port = 3000,
                status = ACTIVE,
                startedAt = now,
                lastHeartbeat = now,
                createdAt = now,
                updatedAt = now,
            )

        val entity = OutboxInstanceEntityMapper.toEntity(instance)

        assertThat(entity.port).isEqualTo(3000)
    }

    @Test
    fun `maps list of entities to instances`() {
        val now = Instant.now()

        val entity1 =
            OutboxInstanceEntity(
                instanceId = "instance-1",
                hostname = "host-1",
                port = 8080,
                status = ACTIVE,
                startedAt = now,
                lastHeartbeat = now,
                createdAt = now,
                updatedAt = now,
            )

        val entity2 =
            OutboxInstanceEntity(
                instanceId = "instance-2",
                hostname = "host-2",
                port = 8081,
                status = SHUTTING_DOWN,
                startedAt = now,
                lastHeartbeat = now,
                createdAt = now,
                updatedAt = now,
            )

        val entities = listOf(entity1, entity2)
        val instances = OutboxInstanceEntityMapper.fromEntities(entities)

        assertThat(instances).hasSize(2)
        assertThat(instances[0].instanceId).isEqualTo("instance-1")
        assertThat(instances[0].hostname).isEqualTo("host-1")
        assertThat(instances[0].port).isEqualTo(8080)
        assertThat(instances[0].status).isEqualTo(ACTIVE)
        assertThat(instances[1].instanceId).isEqualTo("instance-2")
        assertThat(instances[1].hostname).isEqualTo("host-2")
        assertThat(instances[1].port).isEqualTo(8081)
        assertThat(instances[1].status).isEqualTo(SHUTTING_DOWN)
    }

    @Test
    fun `maps empty list of entities to empty list of instances`() {
        val instances = OutboxInstanceEntityMapper.fromEntities(emptyList())

        assertThat(instances).isEmpty()
    }

    @Test
    fun `round trip conversion preserves all data`() {
        val now = Instant.now()

        val originalInstance =
            OutboxInstance(
                instanceId = "round-trip-test",
                hostname = "round-trip-host",
                port = 5432,
                status = ACTIVE,
                startedAt = now.minus(20, MINUTES),
                lastHeartbeat = now.minus(2, MINUTES),
                createdAt = now.minus(25, MINUTES),
                updatedAt = now.minus(1, MINUTES),
            )

        val entity = OutboxInstanceEntityMapper.toEntity(originalInstance)
        val convertedInstance = OutboxInstanceEntityMapper.fromEntity(entity)

        assertThat(convertedInstance).isEqualTo(originalInstance)
    }

    @Test
    fun `maps instances with special hostname characters`() {
        val now = Instant.now()

        val instance =
            OutboxInstance(
                instanceId = "special-host-test",
                hostname = "app-server-01.production.example.com",
                port = 443,
                status = ACTIVE,
                startedAt = now,
                lastHeartbeat = now,
                createdAt = now,
                updatedAt = now,
            )

        val entity = OutboxInstanceEntityMapper.toEntity(instance)

        assertThat(entity.hostname).isEqualTo("app-server-01.production.example.com")
    }
}
