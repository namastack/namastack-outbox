package io.namastack.outbox.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Configures the JDBC completion writer executor when completion batching is enabled.
 *
 * @since 1.12.0
 */
@AutoConfiguration(
    after = [JdbcOutboxAutoConfiguration::class],
    before = [OutboxCoreProcessingAutoConfiguration::class],
)
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "namastack.outbox.jdbc.completion-batching", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(JdbcOutboxConfigurationProperties::class)
class JdbcOutboxCompletionAutoConfiguration {
    @Bean("outboxCompletionExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxCompletionExecutor"])
    internal fun outboxCompletionExecutor(properties: JdbcOutboxConfigurationProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            setThreadNamePrefix("outbox-jdbc-completion-writer-")
            corePoolSize = properties.completionBatching.writerCount
            maxPoolSize = properties.completionBatching.writerCount
            queueCapacity = 0
        }
}
