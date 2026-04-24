package io.namastack.outbox

import io.mockk.mockk
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler

@Configuration
class TaskSchedulerConfiguration {
    @Bean
    fun taskScheduler(): TaskScheduler = mockk(relaxed = true)
}
