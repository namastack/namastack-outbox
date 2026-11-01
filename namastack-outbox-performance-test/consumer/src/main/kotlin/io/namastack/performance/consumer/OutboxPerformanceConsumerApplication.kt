package io.namastack.performance.consumer

import com.zaxxer.hikari.HikariDataSource
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.config.JdbcOutboxConfigurationProperties
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.task.SimpleAsyncTaskExecutor

@SpringBootApplication
class OutboxPerformanceConsumerApplication {
    @Bean
    fun benchmarkSettingsValidator(
        properties: OutboxProperties,
        beans: BeanFactory,
        dataSource: HikariDataSource,
        jdbcProperties: JdbcOutboxConfigurationProperties,
    ) = ApplicationRunner {
        val executor = beans.getBean("outboxTaskExecutor")
        System.getenv("NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT")?.toInt()?.let { requested ->
            check(properties.processing.executorConcurrencyLimit == requested) { "Requested concurrency did not bind" }
            if (System.getenv("PERF_VIRTUAL_THREADS") == "true") {
                check(executor is SimpleAsyncTaskExecutor && executor.concurrencyLimit == requested) {
                    "Runtime executor concurrency differs from requested benchmark settings"
                }
            }
        }
        System.getenv("PERF_DB_POOL_SIZE")?.toInt()?.let { requested ->
            check(dataSource.maximumPoolSize == requested) { "Requested connection pool size did not bind" }
        }
        System.getenv("PERF_COMPLETION_BATCHING")?.toBooleanStrict()?.let { requested ->
            check(jdbcProperties.completionBatching.enabled == requested) { "Completion batching did not bind" }
            val writerType = Class.forName("io.namastack.outbox.JdbcOutboxCompletionBatcher")
            check((beans.getBeanProvider(writerType).getIfAvailable() != null) == requested) {
                "Runtime completion writer differs from requested benchmark settings"
            }
        }
        System.getenv("PERF_COMPLETION_WRITERS")?.toInt()?.let { requested ->
            check(jdbcProperties.completionBatching.writerCount == requested) { "Completion writer count did not bind" }
        }
        System.getenv("NAMASTACK_OUTBOX_JDBC_COMPLETION_BATCHING_BULK_UPDATES")?.toBooleanStrict()?.let { requested ->
            check(jdbcProperties.completionBatching.bulkUpdates == requested) { "Bulk completion updates did not bind" }
        }
        System.getenv("NAMASTACK_OUTBOX_PROCESSING_STOP_ON_FIRST_FAILURE")?.toBooleanStrict()?.let { requested ->
            check(properties.processing.stopOnFirstFailure == requested) { "Strict processing setting did not bind" }
        }
        println(
            "Verified performance settings: instance=${System.getenv("PERF_INSTANCE_ID")}, " +
                "concurrency=${properties.processing.executorConcurrencyLimit}, " +
                "keysPerPoll=${properties.polling.batchSize}, strict=${properties.processing.stopOnFirstFailure}, " +
                "pool=${dataSource.maximumPoolSize}, completionBatching=${jdbcProperties.completionBatching}, " +
                "cpus=${Runtime.getRuntime().availableProcessors()}, " +
                "maxHeap=${Runtime.getRuntime().maxMemory()}",
        )
    }

    @Bean
    fun handlerIdValidator(handlerRegistry: OutboxHandlerRegistry) =
        ApplicationRunner {
            check(handlerRegistry.getHandlerById(PaymentRequestedEventHandler.HANDLER_ID) != null) {
                "Expected performance-test handler is not registered: ${PaymentRequestedEventHandler.HANDLER_ID}"
            }
        }
}

fun main(args: Array<String>) {
    runApplication<OutboxPerformanceConsumerApplication>(*args)
}

