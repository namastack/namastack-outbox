package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties

/**
 * Default [JdbcTableNameResolver] implementation.
 *
 * Applies the schema name and table prefix to the configured base table names, producing fully
 * qualified table names for use in SQL queries. It can be constructed from plain namespace values
 * for programmatic runtimes or from [JdbcOutboxConfigurationProperties] for auto-configuration.
 *
 * @param schemaName Optional database schema containing the outbox tables
 * @param tablePrefix Prefix applied to every outbox table name
 * @param recordTableName Base table name for outbox records
 * @param instanceTableName Base table name for outbox instances
 * @param partitionTableName Base table name for partition assignments
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class DefaultJdbcTableNameResolver(
    schemaName: String? = null,
    tablePrefix: String = "",
    recordTableName: String = "outbox_record",
    instanceTableName: String = "outbox_instance",
    partitionTableName: String = "outbox_partition",
) : JdbcTableNameResolver {
    /**
     * Creates the resolver from the properties used by JDBC auto-configuration.
     *
     * @param properties JDBC outbox configuration properties
     */
    constructor(properties: JdbcOutboxConfigurationProperties) :
        this(
            schemaName = properties.schemaName,
            tablePrefix = properties.tablePrefix,
            recordTableName = properties.tableNames.record,
            instanceTableName = properties.tableNames.instance,
            partitionTableName = properties.tableNames.partition,
        )

    override val outboxRecord: String = resolve(schemaName, tablePrefix, recordTableName)

    override val outboxInstance: String = resolve(schemaName, tablePrefix, instanceTableName)

    override val outboxPartitionAssignment: String = resolve(schemaName, tablePrefix, partitionTableName)

    private fun resolve(
        schemaName: String?,
        tablePrefix: String,
        baseTableName: String,
    ): String {
        val tableName = "$tablePrefix$baseTableName"
        return schemaName?.let { "$it.$tableName" } ?: tableName
    }
}
