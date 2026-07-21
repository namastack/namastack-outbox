package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties

/**
 * Default [JdbcTableNameResolver] implementation.
 *
 * Applies the configured schema name and table prefix to the configured base table names,
 * producing fully qualified table names for use in SQL queries.
 *
 * @param properties Configuration properties containing schema name, table prefix and base table names
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class DefaultJdbcTableNameResolver(
    private val properties: JdbcOutboxConfigurationProperties,
) : JdbcTableNameResolver {
    /**
     * Resolves the fully qualified table name for the given base table name.
     *
     * @param baseTableName The base table name without prefix or schema (e.g., "outbox_record")
     * @return The fully qualified table name with schema and prefix applied
     */
    private fun resolve(baseTableName: String): String {
        val prefixedTable = "${properties.tablePrefix}$baseTableName"
        return properties.schemaName?.let { "$it.$prefixedTable" } ?: prefixedTable
    }

    override val outboxRecord: String by lazy { resolve(properties.tableNames.record) }

    override val outboxInstance: String by lazy { resolve(properties.tableNames.instance) }

    override val outboxPartitionAssignment: String by lazy { resolve(properties.tableNames.partition) }
}
