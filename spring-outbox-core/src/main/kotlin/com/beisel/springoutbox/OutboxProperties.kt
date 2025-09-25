package com.beisel.springoutbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val maxRetries: Int = 3,
    val locking: Locking = Locking(),
    val schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
    data class Locking(
        val extensionSeconds: Long = 5,
        val refreshThreshold: Long = 2,
    )

    data class SchemaInitialization(
        val enabled: Boolean = false,
    )
}
