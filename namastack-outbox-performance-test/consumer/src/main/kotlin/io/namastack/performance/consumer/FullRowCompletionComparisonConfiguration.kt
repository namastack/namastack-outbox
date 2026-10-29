package io.namastack.performance.consumer

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Benchmark-only control: compare the old completion write without rebuilding the library. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["performance.full-row-completion"], havingValue = "true")
class FullRowCompletionComparisonConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun fullRowCompletionComparison(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is OutboxRecordRepository && bean is OutboxRecordStatusRepository) {
                        object : OutboxRecordRepository by bean, OutboxRecordStatusRepository by bean {
                            override fun updateCompletion(record: OutboxRecord<*>): Boolean = bean.update(record)
                        }
                    } else {
                        bean
                    }
            }
    }
}
