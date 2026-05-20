package io.namastack.example.modulith

import io.namastack.outbox.retry.OutboxRetryPolicy
import org.apache.kafka.common.KafkaException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class OutboxRetryConfig {
    @Bean("outboxRetryPolicy")
    fun customRetryPolicy(): OutboxRetryPolicy =
        object : OutboxRetryPolicy {
            override fun shouldRetry(exception: Throwable): Boolean = exception.cause !is KafkaException

            override fun nextDelay(failureCount: Int): Duration = Duration.ofSeconds(5)

            override fun maxRetries(): Int = 3
        }
}
