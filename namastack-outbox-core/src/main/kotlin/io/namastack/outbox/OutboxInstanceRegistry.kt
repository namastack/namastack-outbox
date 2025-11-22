package io.namastack.outbox

import io.namastack.outbox.OutboxInstanceStatus.ACTIVE
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.String

/**
 * Registry service for managing outbox processor instances in a distributed system.
 *
 * Handles:
 * - Instance registration and deregistration
 * - Heartbeat management for detecting live instances
 * - Stale instance detection and cleanup
 * - Graceful shutdown with notification period
 *
 * Enables horizontal scaling across multiple application instances by maintaining
 * a persistent registry of active processors with regular heartbeat updates.
 *
 * @param instanceRepository Repository for persisting instance data
 * @param properties Configuration properties for outbox instance management
 * @param clock Clock for consistent time-based operations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxInstanceRegistry(
    private val instanceRepository: OutboxInstanceRepository,
    private val properties: OutboxProperties,
    private val clock: Clock,
    private val currentInstanceId: String = UUID.randomUUID().toString(),
) {
    private val log = LoggerFactory.getLogger(OutboxInstanceRegistry::class.java)

    private val staleInstanceTimeout = Duration.ofSeconds(properties.instance.staleInstanceTimeoutSeconds)
    private val gracefulShutdownTimeout = Duration.ofSeconds(properties.instance.gracefulShutdownTimeoutSeconds)

    /**
     * Gets all currently active instances.
     */
    fun getActiveInstances(): List<OutboxInstance> = instanceRepository.findActiveInstances()

    fun getCurrentInstanceId() = currentInstanceId

    /**
     * Gets all active instance IDs.
     */
    fun getActiveInstanceIds(): Set<String> = getActiveInstances().map { it.instanceId }.toSet()

    /**
     * Checks if a specific instance is active.
     */
    fun isInstanceActive(instanceId: String): Boolean = instanceRepository.findById(instanceId)?.status == ACTIVE

    /**
     * Gets the count of active instances.
     */
    fun getActiveInstanceCount(): Long = instanceRepository.countByStatus(ACTIVE)

    /**
     * Registers this instance on startup and sets up shutdown hook.
     *
     * Creates an OutboxInstance entry in the repository with the current host
     * and port information, then registers a JVM shutdown hook for graceful cleanup.
     *
     * @throws Exception if registration fails
     */
    @PostConstruct
    fun registerInstance() {
        try {
            val hostname = InetAddress.getLocalHost().hostName
            val port = getApplicationPort()

            val instance =
                OutboxInstance.create(
                    instanceId = currentInstanceId,
                    hostname = hostname,
                    port = port,
                    status = ACTIVE,
                    clock = clock,
                )

            instanceRepository.save(instance)

            log.info("📝 Registered outbox instance: {} on {}:{}", currentInstanceId, hostname, port)

            Runtime.getRuntime().addShutdownHook(Thread { gracefulShutdown() })
        } catch (ex: Exception) {
            log.error("Failed to register instance {}", currentInstanceId, ex)
            throw ex
        }
    }

    /**
     * Sends heartbeat and cleans up stale instances.
     *
     * Executes periodically to keep the current instance alive in the registry
     * and remove instances with stale heartbeats that are no longer responding.
     *
     * Runs on a fixed schedule defined by outbox.instance.heartbeat-interval-seconds.
     */
    @Scheduled(fixedRateString = "\${outbox.instance.heartbeat-interval-seconds:5}000")
    fun performHeartbeatAndCleanup() {
        try {
            sendHeartbeat()
            cleanupStaleInstances()
        } catch (ex: Exception) {
            log.error("Error during heartbeat and cleanup", ex)
        }
    }

    /**
     * Sends heartbeat for current instance.
     *
     * Updates the last heartbeat timestamp in the repository. If the update fails,
     * it indicates the instance was removed and triggers re-registration.
     */
    private fun sendHeartbeat() {
        val now = OffsetDateTime.now(clock)
        val success = instanceRepository.updateHeartbeat(currentInstanceId, now)

        if (success) {
            log.debug("💓 Sent heartbeat for instance {}", currentInstanceId)
        } else {
            log.warn("⚠️ Failed to send heartbeat for instance {} - re-registering", currentInstanceId)
            reregisterInstance()
        }
    }

    /**
     * Cleans up instances with stale heartbeats.
     *
     * Queries the repository for instances whose last heartbeat is older than the
     * configured timeout threshold and removes them from the registry.
     */
    private fun cleanupStaleInstances() {
        val cutoffTime = OffsetDateTime.now(clock).minus(staleInstanceTimeout)
        val staleInstances = instanceRepository.findInstancesWithStaleHeartbeat(cutoffTime)

        staleInstances.forEach { instance ->
            if (instance.instanceId != currentInstanceId) {
                handleStaleInstance(instance)
            }
        }
    }

    /**
     * Handles detection of a stale instance.
     *
     * Logs a warning about the stale instance and removes it from the registry,
     * indicating that it is no longer responding to heartbeat requests.
     *
     * @param instance The stale instance to remove
     */
    private fun handleStaleInstance(instance: OutboxInstance) {
        log.debug(
            "💀 Detected stale instance: {} (last heartbeat: {})",
            instance.instanceId,
            instance.lastHeartbeat,
        )

        try {
            instanceRepository.deleteById(instance.instanceId)
        } catch (_: Exception) {
            log.debug("Could not delete instance {} - it's already deleted.", instance.instanceId)
        }
    }

    /**
     * Re-registers this instance if it was somehow lost.
     *
     * Called when a heartbeat update fails, indicating the instance entry
     * was removed from the registry and needs to be recreated.
     */
    private fun reregisterInstance() {
        registerInstance()
    }

    /**
     * Performs graceful shutdown of this instance.
     *
     * Marks the instance as shutting down in the registry, waits for other instances
     * to notice the status change, then removes it completely. This allows other instances
     * to redistribute work before shutdown completes.
     */
    fun gracefulShutdown() {
        try {
            log.info("🛑 Initiating graceful shutdown for instance {}", currentInstanceId)

            instanceRepository.updateStatus(
                currentInstanceId,
                OutboxInstanceStatus.SHUTTING_DOWN,
                OffsetDateTime.now(clock),
            )

            Thread.sleep(gracefulShutdownTimeout.toMillis())

            instanceRepository.deleteById(currentInstanceId)

            log.info("✅ Graceful shutdown completed for instance {}", currentInstanceId)
        } catch (ex: Exception) {
            log.error("Error during graceful shutdown of instance {}", currentInstanceId, ex)
        }
    }

    /**
     * Gets the application port from system properties.
     *
     * Attempts to retrieve the server.port system property. Falls back to port 8080
     * if the property is not set or cannot be parsed as an integer.
     *
     * @return The application port, or 8080 if unavailable
     */
    private fun getApplicationPort(): Int =
        try {
            System.getProperty("server.port")?.toInt() ?: 8080
        } catch (_: Exception) {
            8080
        }
}
