package io.namastack.outbox.sns

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for SNS outbox integration.
 *
 * @author Roland Beisel
 * @since 1.3.0
 */
@ConfigurationProperties("namastack.outbox.sns")
data class SnsOutboxProperties(
    /**
     * Whether SNS outbox integration is enabled.
     */
    var enabled: Boolean = true,
    /**
     * Default SNS topic ARN for outbox events.
     */
    var defaultTopicArn: String = "arn:aws:sns:us-east-1:000000000000:outbox-events",
    /**
     * Whether to add an `Idempotency-Key` message attribute, defaulting to the persisted record ID.
     *
     * Explicit route headers override the default. Empty or whitespace-only keys are rejected
     * when enabled. Disabled by default and independent of [fifoDeduplicationIdEnabled].
     */
    var idempotencyHeaderEnabled: Boolean = false,
    /**
     * Whether to supply a native deduplication ID for FIFO topics.
     *
     * Uses an explicit SNS deduplication header, then `Idempotency-Key`, then the persisted record ID.
     * Empty or whitespace-only explicit keys are rejected. Standard topics retain their behavior.
     * Disabled by default and independent of [idempotencyHeaderEnabled]. SNS deduplication is limited
     * to its five-minute window; consumers still need to handle duplicates beyond that window.
     */
    var fifoDeduplicationIdEnabled: Boolean = false,
)
