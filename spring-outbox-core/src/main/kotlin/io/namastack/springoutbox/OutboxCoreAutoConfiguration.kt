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

@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    @ConditionalOnMissingBean
    fun retryPolicy(properties: OutboxProperties): OutboxRetryPolicy =
        OutboxRetryPolicyFactory.create(name = properties.retry.policy, retryProperties = properties.retry)

    @Bean
    @ConditionalOnBean(OutboxLockRepository::class)
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
