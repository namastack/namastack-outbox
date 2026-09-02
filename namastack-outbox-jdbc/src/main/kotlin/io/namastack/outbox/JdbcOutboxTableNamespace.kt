package io.namastack.outbox

/**
 * Resolves one physical table namespace for JDBC outbox repositories.
 *
 * The optional schema is applied to all three tables and the prefix is applied before each
 * configured base table name. Components are trusted configuration and retain database-specific
 * identifier syntax.
 *
 * @property schemaName Optional database schema containing the outbox tables
 * @property tablePrefix Prefix applied to every outbox table name
 * @property recordTableName Base table name for outbox records
 * @property instanceTableName Base table name for outbox instances
 * @property partitionTableName Base table name for partition assignments
 * @author Roland Beisel
 * @since 1.10.0
 */
class JdbcOutboxTableNamespace(
    val schemaName: String? = null,
    val tablePrefix: String = "",
    val recordTableName: String = "outbox_record",
    val instanceTableName: String = "outbox_instance",
    val partitionTableName: String = "outbox_partition",
) : JdbcTableNameResolver {
    override val outboxRecord: String = resolve(recordTableName)

    override val outboxInstance: String = resolve(instanceTableName)

    override val outboxPartitionAssignment: String = resolve(partitionTableName)

    private fun resolve(baseTableName: String): String {
        val tableName = "$tablePrefix$baseTableName"
        return schemaName?.let { "$it.$tableName" } ?: tableName
    }
}
