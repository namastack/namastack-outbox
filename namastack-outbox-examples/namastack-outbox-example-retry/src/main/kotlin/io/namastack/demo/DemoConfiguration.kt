package io.namastack.demo

import io.namastack.outbox.retry.ExponentialBackoffRetryPolicy
import io.namastack.outbox.retry.FixedDelayRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class DemoConfiguration {
    /**
     * Override for default outbox retry policy
     */
    @Bean
    fun outboxRetryPolicy(): OutboxRetryPolicy =
        FixedDelayRetryPolicy
            .builder()
            .maxRetries(1)
            .excludeException(RuntimeException::class)
            .jitter(Duration.ofSeconds(1))
            .build()

    /**
     * Custom outbox retry policy
     */
    @Bean
    fun customOutboxRetryPolicy(): OutboxRetryPolicy =
        ExponentialBackoffRetryPolicy
            .builder()
            .maxRetries(2)
            .initialDelay(Duration.ofSeconds(1))
            .backoffMultiplier(1.5)
            .maxDelay(Duration.ofSeconds(60))
            .includeException(RuntimeException::class)
            .jitter(Duration.ofSeconds(1))
            .build()
}
