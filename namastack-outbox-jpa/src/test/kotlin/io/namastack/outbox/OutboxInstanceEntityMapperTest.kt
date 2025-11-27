package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.DEAD
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class OutboxInstanceEntityMapperTest {
    @Test
    fun `maps OutboxInstance to OutboxInstanceEntity with all properties`() {
        val now = OffsetDateTime.now()
        val startedAt = now.minusMinutes(10)
        val lastHeartbeat = now.minusMinutes(1)
        val createdAt = now.minusMinutes(15)
        val updatedAt = now.minusSeconds(30)

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
        val now = OffsetDateTime.now()
        val startedAt = now.minusMinutes(5)
        val lastHeartbeat = now.minusSeconds(30)
        val createdAt = now.minusMinutes(10)
        val updatedAt = now.minusSeconds(15)

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
        val now = OffsetDateTime.now()

        val instance =
            OutboxInstance(
                instanceId = "dead-instance",
                hostname = "failed-server",
                port = 7070,
                status = DEAD,
                startedAt = now.minusHours(1),
                lastHeartbeat = now.minusMinutes(30),
                createdAt = now.minusHours(2),
                updatedAt = now.minusMinutes(30),
            )

        val entity = OutboxInstanceEntityMapper.toEntity(instance)

        assertThat(entity.instanceId).isEqualTo("dead-instance")
        assertThat(entity.hostname).isEqualTo("failed-server")
        assertThat(entity.port).isEqualTo(7070)
        assertThat(entity.status).isEqualTo(DEAD)
    }

    @Test
    fun `maps OutboxInstance with different port numbers`() {
        val now = OffsetDateTime.now()

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
        val now = OffsetDateTime.now()

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
        val now = OffsetDateTime.now()

        val originalInstance =
            OutboxInstance(
                instanceId = "round-trip-test",
                hostname = "round-trip-host",
                port = 5432,
                status = ACTIVE,
                startedAt = now.minusMinutes(20),
                lastHeartbeat = now.minusMinutes(2),
                createdAt = now.minusMinutes(25),
                updatedAt = now.minusMinutes(1),
            )

        val entity = OutboxInstanceEntityMapper.toEntity(originalInstance)
        val convertedInstance = OutboxInstanceEntityMapper.fromEntity(entity)

        assertThat(convertedInstance).isEqualTo(originalInstance)
    }

    @Test
    fun `maps instances with special hostname characters`() {
        val now = OffsetDateTime.now()

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
