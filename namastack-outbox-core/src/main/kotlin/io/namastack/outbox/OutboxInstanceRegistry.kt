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
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry service for managing outbox processor instances.
 *
 * Handles instance registration, heartbeat management for horizontal scaling
 * across multiple physical application instances.
 *
 * @param instanceRepository Repository for persisting instance data
 * @param properties Configuration properties for outbox functionality
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxInstanceRegistry(
    private val instanceRepository: OutboxInstanceRepository,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxInstanceRegistry::class.java)

    private val currentInstanceId = generateInstanceId()
    private val knownInstances = ConcurrentHashMap<String, OffsetDateTime>()

    private val staleInstanceTimeout = Duration.ofSeconds(properties.instance.staleInstanceTimeoutSeconds)
    private val gracefulShutdownTimeout = Duration.ofSeconds(properties.instance.gracefulShutdownTimeoutSeconds)

    /**
     * Gets the current instance ID.
     */
    fun getCurrentInstanceId(): String = currentInstanceId

    /**
     * Gets all currently active instances.
     */
    fun getActiveInstances(): List<OutboxInstance> = instanceRepository.findActiveInstances()

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
     * Registers this instance on startup.
     */
    @PostConstruct
    fun registerInstance() {
        try {
            val now = OffsetDateTime.now(clock)
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
            knownInstances[currentInstanceId] = now

            log.info("📝 Registered outbox instance: {} on {}:{}", currentInstanceId, hostname, port)

            // Setup shutdown hook for graceful deregistration
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    gracefulShutdown()
                },
            )
        } catch (ex: Exception) {
            log.error("Failed to register instance {}", currentInstanceId, ex)
            throw ex
        }
    }

    /**
     * Sends heartbeat and cleans up stale instances.
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
     */
    private fun sendHeartbeat() {
        val now = OffsetDateTime.now(clock)
        val success = instanceRepository.updateHeartbeat(currentInstanceId, now)

        if (success) {
            knownInstances[currentInstanceId] = now
            log.debug("💓 Sent heartbeat for instance {}", currentInstanceId)
        } else {
            log.warn("⚠️ Failed to send heartbeat for instance {} - re-registering", currentInstanceId)
            reregisterInstance()
        }
    }

    /**
     * Cleans up instances with stale heartbeats.
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
     */
    private fun handleStaleInstance(instance: OutboxInstance) {
        log.warn(
            "💀 Detected stale instance: {} (last heartbeat: {})",
            instance.instanceId,
            instance.lastHeartbeat,
        )

        // Mark as dead and remove
        instanceRepository.updateStatus(
            instance.instanceId,
            OutboxInstanceStatus.DEAD,
            OffsetDateTime.now(clock),
        )
        instanceRepository.deleteById(instance.instanceId)
        knownInstances.remove(instance.instanceId)
    }

    /**
     * Re-registers this instance if it was somehow lost.
     */
    private fun reregisterInstance() {
        registerInstance()
    }

    /**
     * Performs graceful shutdown of this instance.
     */
    fun gracefulShutdown() {
        try {
            log.info("🛑 Initiating graceful shutdown for instance {}", currentInstanceId)

            // Mark as shutting down
            instanceRepository.updateStatus(
                currentInstanceId,
                OutboxInstanceStatus.SHUTTING_DOWN,
                OffsetDateTime.now(clock),
            )

            // Wait a bit for other instances to notice
            Thread.sleep(gracefulShutdownTimeout.toMillis())

            // Remove from registry
            instanceRepository.deleteById(currentInstanceId)
            knownInstances.remove(currentInstanceId)

            log.info("✅ Graceful shutdown completed for instance {}", currentInstanceId)
        } catch (ex: Exception) {
            log.error("Error during graceful shutdown of instance {}", currentInstanceId, ex)
        }
    }

    /**
     * Detects new instances that joined.
     */
    @Scheduled(fixedRateString = "\${outbox.instance.new-instance-detection-interval-seconds:10}000")
    fun detectNewInstances() {
        try {
            val currentActive = getActiveInstances()
            val currentIds = currentActive.map { it.instanceId }.toSet()
            val knownIds = knownInstances.keys.toSet()

            // Find new instances
            val newInstanceIds = currentIds - knownIds
            newInstanceIds.forEach { instanceId ->
                val instance = currentActive.find { it.instanceId == instanceId }
                if (instance != null) {
                    handleNewInstance(instance)
                }
            }

            // Update known instances
            currentActive.forEach { instance ->
                knownInstances[instance.instanceId] = instance.lastHeartbeat
            }
        } catch (ex: Exception) {
            log.error("Error detecting new instances", ex)
        }
    }

    /**
     * Handles detection of a new instance.
     */
    private fun handleNewInstance(instance: OutboxInstance) {
        if (instance.instanceId != currentInstanceId) {
            log.info(
                "🆕 Detected new instance: {} on {}:{}",
                instance.instanceId,
                instance.hostname,
                instance.port,
            )
        }
    }

    /**
     * Generates a unique instance ID.
     */
    private fun generateInstanceId(): String = UUID.randomUUID().toString()

    /**
     * Gets the application port (with fallback).
     */
    private fun getApplicationPort(): Int =
        try {
            System.getProperty("server.port")?.toInt() ?: 8080
        } catch (_: Exception) {
            8080 // Fallback
        }
}
