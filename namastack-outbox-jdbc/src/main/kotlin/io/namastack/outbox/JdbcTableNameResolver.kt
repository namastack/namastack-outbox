package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties

/**
 * Helper class for resolving fully qualified table names based on configuration.
 *
 * This resolver applies the configured schema name and table prefix to base table names,
 * producing fully qualified table names for use in SQL queries.
 *
 * @param properties Configuration properties containing schema name and table prefix
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal class JdbcTableNameResolver(
    private val properties: JdbcOutboxConfigurationProperties,
) {
    /**
     * Resolves the fully qualified table name for the given base table name.
     *
     * @param baseTableName The base table name without prefix or schema (e.g., "outbox_record")
     * @return The fully qualified table name with schema and prefix applied
     */
    fun resolve(baseTableName: String): String {
        val prefixedTable = "${properties.tablePrefix}$baseTableName"
        return properties.schemaName?.let { "$it.$prefixedTable" } ?: prefixedTable
    }

    /**
     * Table name for outbox records.
     */
    val outboxRecord: String by lazy { resolve("outbox_record") }

    /**
     * Table name for outbox instances.
     */
    val outboxInstance: String by lazy { resolve("outbox_instance") }

    /**
     * Table name for outbox partition assignments.
     */
    val outboxPartitionAssignment: String by lazy { resolve("outbox_partition") }
}
