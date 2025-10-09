package io.namastack.springoutbox

import io.namastack.springoutbox.lock.OutboxLockManager
import io.namastack.springoutbox.lock.OutboxLockRepository
import io.namastack.springoutbox.retry.OutboxRetryPolicy
import io.namastack.springoutbox.retry.OutboxRetryPolicyFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Auto-configuration class for Spring Outbox core functionality.
 *
 * This configuration is activated when the @EnableOutbox annotation is present
 * and provides all necessary beans for the outbox pattern implementation.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a retry policy based on configuration properties.
     *
     * @param properties Outbox configuration properties
     * @return Configured retry policy
     */
    @Bean
    @ConditionalOnMissingBean
    fun retryPolicy(properties: OutboxProperties): OutboxRetryPolicy =
        OutboxRetryPolicyFactory.create(name = properties.retry.policy, retryProperties = properties.retry)

    /**
     * Creates the outbox lock manager when a lock repository is available.
     *
     * @param lockRepository Repository for managing locks
     * @param properties Outbox configuration properties
     * @param clock Clock for time-based operations
     * @return Configured outbox lock manager
     */
    @Bean
    @ConditionalOnBean(OutboxLockRepository::class)
    fun outboxLockManager(
        lockRepository: OutboxLockRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxLockManager = OutboxLockManager(lockRepository, properties, clock)

    /**
     * Creates the outbox processing scheduler when required dependencies are available.
     *
     * @param recordRepository Repository for outbox records
     * @param recordProcessor Processor for handling records
     * @param lockManager Manager for acquiring locks
     * @param retryPolicy Policy for retry behavior
     * @param properties Configuration properties
     * @param clock Clock for time-based operations
     * @return Configured outbox processing scheduler
     */
    @Bean
    @ConditionalOnBean(OutboxRecordRepository::class)
    fun outboxScheduler(
        recordRepository: OutboxRecordRepository,
        recordProcessor: OutboxRecordProcessor,
        lockManager: OutboxLockManager,
        retryPolicy: OutboxRetryPolicy,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(recordRepository, recordProcessor, lockManager, retryPolicy, properties, clock)
}
