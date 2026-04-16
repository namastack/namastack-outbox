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
)
