package io.namastack.performance

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskExecutor

@Configuration
class ExecutorConfiguration {
    @Bean(name = ["outboxTaskExecutor"])
    fun outboxTaskExecutor(): TaskExecutor =
        SimpleAsyncTaskExecutor().apply {
            concurrencyLimit = 5
            setVirtualThreads(true)
        }
}
