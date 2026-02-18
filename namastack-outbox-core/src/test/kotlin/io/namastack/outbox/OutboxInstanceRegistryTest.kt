package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.DEAD
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@DisplayName("OutboxInstanceRegistry")
class OutboxInstanceRegistryTest {
    private val clock = Clock.fixed(Instant.parse("2025-10-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = Instant.now(clock)

    private val instanceRepository = mockk<OutboxInstanceRepository>()
    private val properties =
        OutboxProperties(
            instance =
                OutboxProperties.Instance(
                    staleInstanceTimeoutSeconds = 2,
                    heartbeatIntervalSeconds = 1,
                ),
        )

    private lateinit var registry: OutboxInstanceRegistry

    @BeforeEach
    fun setUp() {
        registry = OutboxInstanceRegistry(instanceRepository, properties, clock)

        every { instanceRepository.save(any()) } returns mockk()
        every { instanceRepository.findActiveInstances() } returns emptyList()
        every { instanceRepository.updateHeartbeat(any(), any()) } returns true
        every { instanceRepository.updateStatus(any(), any(), any()) } returns true
        every { instanceRepository.deleteById(any()) } returns true
        every { instanceRepository.findInstancesWithStaleHeartbeat(any()) } returns emptyList()
    }

    @Nested
    @DisplayName("Instance Registration")
    inner class InstanceRegistration {
        @Test
        fun `register instance on startup`() {
            registry.registerInstance()

            verify(exactly = 1) { instanceRepository.save(any()) }
            assertThat(registry.getCurrentInstanceId()).isNotNull
        }

        @Test
        fun `handle registration failure gracefully`() {
            every { instanceRepository.save(any()) } throws RuntimeException("Database error")

            try {
                registry.registerInstance()
            } catch (ex: Exception) {
                assertThat(ex).hasMessage("Database error")
            }
        }
    }

    @Nested
    @DisplayName("Active Instance Management")
    inner class ActiveInstanceManagement {
        @Test
        fun `get active instances`() {
            val instance1 = createInstance("instance-1")
            val instance2 = createInstance("instance-2")

            every { instanceRepository.findActiveInstances() } returns listOf(instance1, instance2)

            val activeInstances = registry.getActiveInstances()

            assertThat(activeInstances).hasSize(2)
            assertThat(activeInstances).containsExactly(instance1, instance2)
        }

        @Test
        fun `get active instance IDs`() {
            val instance1 = createInstance("instance-1")
            val instance2 = createInstance("instance-2")

            every { instanceRepository.findActiveInstances() } returns listOf(instance1, instance2)

            val instanceIds = registry.getActiveInstanceIds()

            assertThat(instanceIds).containsExactly("instance-1", "instance-2")
        }

        @Test
        fun `check if instance is active`() {
            val activeInstance = createInstance("active-instance")

            every { instanceRepository.findById("active-instance") } returns activeInstance
            every { instanceRepository.findById("inactive-instance") } returns null

            assertThat(registry.isInstanceActive("active-instance")).isTrue()
            assertThat(registry.isInstanceActive("inactive-instance")).isFalse()
        }

        @Test
        fun `get active instance count`() {
            every { instanceRepository.countByStatus(ACTIVE) } returns 3L

            val count = registry.getActiveInstanceCount()

            assertThat(count).isEqualTo(3L)
        }

        @Test
        fun `return empty list when no active instances`() {
            every { instanceRepository.findActiveInstances() } returns emptyList()

            val activeInstances = registry.getActiveInstances()

            assertThat(activeInstances).isEmpty()
        }
    }

    @Nested
    @DisplayName("Heartbeat Management")
    inner class HeartbeatManagement {
        @Test
        fun `send successful heartbeat`() {
            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.updateHeartbeat(any(), now) }
        }

        @Test
        fun `handle heartbeat failure and re-register`() {
            every { instanceRepository.updateHeartbeat(any(), any()) } returns false

            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.updateHeartbeat(any(), now) }
            verify(exactly = 1) { instanceRepository.save(any()) }
        }

        @Test
        fun `handle heartbeat exception gracefully`() {
            every { instanceRepository.updateHeartbeat(any(), any()) } throws RuntimeException("DB error")

            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.updateHeartbeat(any(), now) }
        }
    }

    @Nested
    @DisplayName("Stale Instance Cleanup")
    inner class StaleInstanceCleanup {
        @Test
        fun `clean up stale instances`() {
            val staleInstance = createInstance("stale-instance")
            val cutoffTime = now.minus(Duration.ofSeconds(2))

            every { instanceRepository.findInstancesWithStaleHeartbeat(cutoffTime) } returns listOf(staleInstance)

            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.deleteById("stale-instance") }
        }

        @Test
        fun `not clean up current instance even if stale`() {
            val currentInstanceId = registry.getCurrentInstanceId()
            val currentInstance = createInstance(currentInstanceId)
            val cutoffTime = now.minus(Duration.ofSeconds(2))

            every { instanceRepository.findInstancesWithStaleHeartbeat(cutoffTime) } returns listOf(currentInstance)

            registry.performHeartbeatAndCleanup()

            verify(exactly = 0) { instanceRepository.updateStatus(currentInstanceId, DEAD, any()) }
            verify(exactly = 0) { instanceRepository.deleteById(currentInstanceId) }
        }

        @Test
        fun `handle multiple stale instances`() {
            val staleInstance1 = createInstance("stale-1")
            val staleInstance2 = createInstance("stale-2")
            val cutoffTime = now.minus(Duration.ofSeconds(2))

            every { instanceRepository.findInstancesWithStaleHeartbeat(cutoffTime) } returns
                listOf(staleInstance1, staleInstance2)

            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.deleteById("stale-1") }
            verify(exactly = 1) { instanceRepository.deleteById("stale-2") }
        }

        @Test
        fun `skip cleanup when no stale instances`() {
            val cutoffTime = now.minus(Duration.ofSeconds(5))

            every { instanceRepository.findInstancesWithStaleHeartbeat(cutoffTime) } returns emptyList()

            registry.performHeartbeatAndCleanup()

            verify(exactly = 0) { instanceRepository.updateStatus(any(), DEAD, any()) }
            verify(exactly = 0) { instanceRepository.deleteById(any()) }
        }

        @Test
        fun `handle exception in handleStaleInstance gracefully`() {
            val staleInstance = createInstance("stale-instance")
            every { instanceRepository.findInstancesWithStaleHeartbeat(any()) } returns listOf(staleInstance)
            every { instanceRepository.deleteById("stale-instance") } throws RuntimeException("Delete error")

            // Should not throw
            registry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.deleteById("stale-instance") }
        }
    }

    @Nested
    @DisplayName("Graceful Shutdown")
    inner class GracefulShutdown {
        @Test
        fun `perform graceful shutdown`() {
            registry.gracefulShutdown()

            verify(exactly = 1) {
                instanceRepository.updateStatus(
                    instanceId = registry.getCurrentInstanceId(),
                    status = SHUTTING_DOWN,
                    timestamp = now,
                )
            }
            verify(exactly = 1) { instanceRepository.deleteById(registry.getCurrentInstanceId()) }
        }

        @Test
        fun `handle shutdown exception gracefully`() {
            every { instanceRepository.updateStatus(any(), any(), any()) } throws RuntimeException("DB error")

            registry.gracefulShutdown()

            verify(exactly = 1) {
                instanceRepository.updateStatus(
                    instanceId = registry.getCurrentInstanceId(),
                    status = SHUTTING_DOWN,
                    timestamp = now,
                )
            }
        }

        @Test
        fun `handle delete exception during shutdown`() {
            every { instanceRepository.deleteById(any()) } throws RuntimeException("Delete error")

            registry.gracefulShutdown()

            verify(exactly = 1) {
                instanceRepository.updateStatus(
                    instanceId = registry.getCurrentInstanceId(),
                    status = SHUTTING_DOWN,
                    timestamp = now,
                )
            }
            verify(exactly = 1) { instanceRepository.deleteById(registry.getCurrentInstanceId()) }
        }
    }

    @Nested
    @DisplayName("Configuration Properties")
    inner class ConfigurationProperties {
        @Test
        fun `use custom graceful shutdown timeout`() {
            val customProperties =
                properties.copy(
                    instance = properties.instance.copy(gracefulShutdownTimeoutSeconds = 2),
                )
            val customRegistry = OutboxInstanceRegistry(instanceRepository, customProperties, clock)

            customRegistry.gracefulShutdown()

            verify(exactly = 1) { instanceRepository.updateStatus(any(), SHUTTING_DOWN, now) }
            verify(exactly = 1) { instanceRepository.deleteById(any()) }
        }

        @Test
        fun `use custom stale instance timeout`() {
            val customProperties =
                properties.copy(
                    instance = properties.instance.copy(staleInstanceTimeoutSeconds = 5),
                )
            val customRegistry = OutboxInstanceRegistry(instanceRepository, customProperties, clock)
            val expectedCutoff = now.minus(Duration.ofSeconds(5))

            customRegistry.performHeartbeatAndCleanup()

            verify(exactly = 1) { instanceRepository.findInstancesWithStaleHeartbeat(expectedCutoff) }
        }
    }

    @Nested
    @DisplayName("Error Scenarios")
    inner class ErrorScenarios {
        @Test
        fun `handle repository failures during heartbeat`() {
            every { instanceRepository.updateHeartbeat(any(), any()) } throws RuntimeException("Connection lost")

            registry.performHeartbeatAndCleanup()

            // Should not propagate exception
        }

        @Test
        fun `handle repository failures during cleanup`() {
            every { instanceRepository.findInstancesWithStaleHeartbeat(any()) } throws RuntimeException("Query failed")

            registry.performHeartbeatAndCleanup()

            // Should not propagate exception
        }
    }

    private fun createInstance(instanceId: String): OutboxInstance =
        OutboxInstance.create(
            instanceId = instanceId,
            hostname = "test-host",
            port = 8080,
            status = ACTIVE,
            clock = clock,
        )
}
