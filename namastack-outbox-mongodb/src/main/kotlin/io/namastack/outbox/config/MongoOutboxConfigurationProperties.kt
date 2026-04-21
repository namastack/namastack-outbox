package io.namastack.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the MongoDB outbox module.
 *
 * @property collectionPrefix Prefix to apply to all outbox collection names
 *                            (e.g., "my_" results in "my_outbox_records").
 *                            Defaults to empty string (no prefix).
 *
 * @author Roland Beisel
 * @since 1.5.0
 */
@ConfigurationProperties(prefix = "namastack.outbox.mongodb")
data class MongoOutboxConfigurationProperties(
    var collectionPrefix: String = "",
)
