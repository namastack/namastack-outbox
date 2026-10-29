package io.namastack.performance.consumer

import io.namastack.outbox.JdbcOutboxRecordEntityMapper
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["performance.tuning-repository"], havingValue = "true")
class TuningRepositoryConfiguration {
    @Bean(name = ["performanceCompletionBatcher"], destroyMethod = "close")
    @Lazy
    internal fun completionBatcher(factory: BeanFactory): CompletionBatcher {
        val settings = TuningSettings.fromEnvironment()
        return CompletionBatcher(
            factory.getBean("outboxJdbcClient", JdbcClient::class.java),
            TransactionTemplate(factory.getBean(PlatformTransactionManager::class.java)),
            settings.completionBatchSize,
            settings.completionBatchWaitMicros,
            useArrays = System.getenv("PERF_COMPLETION_ARRAY_PARAMETERS") == "true",
        )
    }

    companion object {
        @Bean
        @JvmStatic
        fun tuningRepository(factory: BeanFactory): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is OutboxRecordRepository && bean is OutboxRecordStatusRepository) {
                        PostgresTuningRepository(
                            bean,
                            bean,
                            factory.getBean("outboxJdbcClient", JdbcClient::class.java),
                            JdbcOutboxRecordEntityMapper(factory.getBean(OutboxPayloadSerializer::class.java)),
                            TransactionTemplate(factory.getBean(PlatformTransactionManager::class.java)),
                            factory.getBean(Clock::class.java),
                            TuningSettings.fromEnvironment(),
                            if (TuningSettings.fromEnvironment().completionBatchSize > 0) {
                                factory.getBean("performanceCompletionBatcher", CompletionBatcher::class.java)
                            } else {
                                null
                            },
                        )
                    } else {
                        bean
                    }
            }
    }
}
