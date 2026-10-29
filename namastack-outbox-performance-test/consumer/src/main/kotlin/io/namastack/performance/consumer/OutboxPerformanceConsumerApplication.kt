package io.namastack.performance.consumer

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
        jdbc: JdbcOutboxConfigurationProperties,
        beans: BeanFactory,
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
        System.getenv("PERF_NATIVE_RECORD_FETCH_BATCH_SIZE")?.toInt()?.let { requested ->
            check(properties.polling.recordFetchBatchSize == requested) { "Bulk fetch size did not bind" }
        }
        System.getenv("PERF_NATIVE_COMPLETION_BATCHING")?.toBooleanStrict()?.let { requested ->
            check(jdbc.completionBatching.enabled == requested) { "Native completion batching did not bind" }
        }
        if (jdbc.completionBatching.enabled) {
            System.getenv("PERF_NATIVE_COMPLETION_BATCH_SIZE")?.toInt()?.let { requested ->
                check(jdbc.completionBatching.maxBatchSize == requested) { "Completion batch size did not bind" }
            }
        }
        println(
            "Verified performance settings: instance=${System.getenv("PERF_INSTANCE_ID")}, " +
                "concurrency=${properties.processing.executorConcurrencyLimit}, " +
                "fetchKeys=${properties.polling.recordFetchBatchSize}, " +
                "nativeBatching=${jdbc.completionBatching.enabled}, " +
                "completionBatch=${jdbc.completionBatching.maxBatchSize}, " +
                "completionWait=${jdbc.completionBatching.maxWait}, " +
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
