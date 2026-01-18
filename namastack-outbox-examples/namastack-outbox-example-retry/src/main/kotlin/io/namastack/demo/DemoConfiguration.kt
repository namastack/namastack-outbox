package io.namastack.demo

import io.namastack.outbox.retry.BackOffStrategies
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import java.time.Duration

@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class DemoConfiguration {
    /**
     * Override for default outbox retry policy
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun outboxRetryPolicy(): OutboxRetryPolicy =
        OutboxRetryPolicy
            .builder()
            .maxRetries(1)
            .noRetryOn(RuntimeException::class)
            .withJitter(Duration.ofSeconds(1))
            .build()

    /**
     * Custom outbox retry policy
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun customOutboxRetryPolicy(): OutboxRetryPolicy =
        OutboxRetryPolicy
            .builder()
            .maxRetries(2)
            .backOff(
                BackOffStrategies.exponential(
                    initialDelay = Duration.ofSeconds(1),
                    multiplier = 1.5,
                    maxDelay = Duration.ofSeconds(60),
                ),
            ).retryOn(RuntimeException::class)
            .withJitter(Duration.ofSeconds(1))
            .build()
}
