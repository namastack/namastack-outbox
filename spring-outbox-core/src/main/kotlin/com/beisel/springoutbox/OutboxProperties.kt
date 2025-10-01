package com.beisel.springoutbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val locking: Locking = Locking(),
    val retry: Retry = Retry(),
) {
    data class Locking(
        val extensionSeconds: Long = 5,
        val refreshThreshold: Long = 2,
    )

    data class Retry(
        val maxRetries: Int = 3,
        val policy: String = "fixed",
        val initialDelay: Long = 1000,
        val maxDelay: Long = 60000,
        val jitter: Long = 500,
    )
}
