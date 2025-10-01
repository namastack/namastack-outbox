package com.beisel.springoutbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val locking: Locking = Locking(),
    val retry: Retry = Retry(),
    val processing: Processing = Processing(),
) {
    data class Locking(
        val extensionSeconds: Long = 5,
        val refreshThreshold: Long = 2,
    )

    data class Retry(
        val maxRetries: Int = 3,
        val policy: String = "exponential",
        val exponential: ExponentialRetry = ExponentialRetry(),
        val fixed: FixedRetry = FixedRetry(),
        val jittered: JitteredRetry = JitteredRetry(),
    ) {
        data class ExponentialRetry(
            val initialDelay: Long = 1000,
            val maxDelay: Long = 60000,
            val multiplier: Double = 2.0,
        )

        data class FixedRetry(
            val delay: Long = 5000,
        )

        data class JitteredRetry(
            val basePolicy: String = "exponential",
            val jitter: Long = 500,
        )
    }

    data class Processing(
        val stopOnFirstFailure: Boolean = true,
    )
}
