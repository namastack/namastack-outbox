package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLockManager
import com.beisel.springoutbox.lock.OutboxLockRepository
import com.beisel.springoutbox.retry.ExponentialBackoffRetryPolicy
import com.beisel.springoutbox.retry.FixedDelayRetryPolicy
import com.beisel.springoutbox.retry.OutboxRetryPolicy
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration

@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun retryPolicy(properties: OutboxProperties): OutboxRetryPolicy {
        val retryProperties = properties.retry

        return when (retryProperties.policy) {
            "fixed" -> FixedDelayRetryPolicy(Duration.ofMillis(retryProperties.initialDelay))

            "exponential" ->
                ExponentialBackoffRetryPolicy(
                    Duration.ofMillis(retryProperties.initialDelay),
                    Duration.ofMillis(retryProperties.maxDelay),
                )

            else -> FixedDelayRetryPolicy(Duration.ofMillis(retryProperties.initialDelay))
        }
    }

    @Bean
    fun outboxLockManager(
        lockRepository: OutboxLockRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxLockManager = OutboxLockManager(lockRepository, properties, clock)

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
