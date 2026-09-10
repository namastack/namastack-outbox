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
    properties: JdbcOutboxConfigurationProperties,
) : JdbcTableNameResolver {
    private val namespace =
        JdbcOutboxTableNamespace(
            schemaName = properties.schemaName,
            tablePrefix = properties.tablePrefix,
            recordTableName = properties.tableNames.record,
            instanceTableName = properties.tableNames.instance,
            partitionTableName = properties.tableNames.partition,
        )

    override val outboxRecord: String = namespace.outboxRecord

    override val outboxInstance: String = namespace.outboxInstance

    override val outboxPartitionAssignment: String = namespace.outboxPartitionAssignment
}
