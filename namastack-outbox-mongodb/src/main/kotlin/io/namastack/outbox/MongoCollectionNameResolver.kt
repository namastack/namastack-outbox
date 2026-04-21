package io.namastack.outbox

import io.namastack.outbox.config.MongoOutboxConfigurationProperties

/**
 * Helper class for resolving MongoDB collection names based on configuration.
 *
 * This resolver applies the configured collection prefix to base collection names,
 * producing fully qualified collection names for use in MongoDB operations.
 *
 * @param properties Configuration properties containing collection prefix
 *
 * @author Roland Beisel
 * @since 1.5.0
 */
internal class MongoCollectionNameResolver(
    private val properties: MongoOutboxConfigurationProperties,
) {
    /**
     * Resolves the collection name for the given base name.
     *
     * @param baseCollectionName The base collection name without prefix (e.g., "outbox_records")
     * @return The collection name with prefix applied
     */
    fun resolve(baseCollectionName: String): String = "${properties.collectionPrefix}$baseCollectionName"

    /**
     * Collection name for outbox records.
     */
    val outboxRecords: String by lazy { resolve("outbox_records") }

    /**
     * Collection name for outbox instances.
     */
    val outboxInstances: String by lazy { resolve("outbox_instances") }

    /**
     * Collection name for outbox partition assignments.
     */
    val outboxPartitionAssignments: String by lazy { resolve("outbox_partition_assignments") }
}
