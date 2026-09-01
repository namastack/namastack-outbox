package io.namastack.outbox

/**
 * Resolves one validated physical table namespace for JDBC outbox repositories.
 *
 * Names are restricted to portable unquoted SQL identifiers. The optional schema is applied to all
 * three tables and the prefix is applied before each configured base table name.
 *
 * @property schemaName Optional database schema containing the outbox tables
 * @property tablePrefix Prefix applied to every outbox table name
 * @property recordTableName Base table name for outbox records
 * @property instanceTableName Base table name for outbox instances
 * @property partitionTableName Base table name for partition assignments
 * @throws IllegalArgumentException if any namespace component is not a valid SQL identifier
 *
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
    init {
        schemaName?.let { requireIdentifier("schema name", it) }
        requirePrefix(tablePrefix)
        requireIdentifier("record table name", recordTableName)
        requireIdentifier("instance table name", instanceTableName)
        requireIdentifier("partition table name", partitionTableName)
    }

    override val outboxRecord: String = resolve(recordTableName)

    override val outboxInstance: String = resolve(instanceTableName)

    override val outboxPartitionAssignment: String = resolve(partitionTableName)

    private fun resolve(baseTableName: String): String {
        val tableName = "$tablePrefix$baseTableName"
        requireIdentifier("resolved table name", tableName)
        return schemaName?.let { "$it.$tableName" } ?: tableName
    }

    companion object {
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val PREFIX_PATTERN = Regex("[A-Za-z0-9_]*")

        /**
         * Validates a resolved, optionally schema-qualified table name.
         *
         * @param tableName Resolved table name supplied by a [JdbcTableNameResolver]
         * @return The unchanged validated table name
         * @throws IllegalArgumentException if the value is not a valid table identifier
         */
        internal fun requireValidTableName(tableName: String): String {
            val identifiers = tableName.split('.')
            require(identifiers.size in 1..2 && identifiers.all(IDENTIFIER_PATTERN::matches)) {
                "Invalid JDBC outbox table name '$tableName': expected an optionally schema-qualified SQL identifier"
            }
            return tableName
        }

        private fun requireIdentifier(
            description: String,
            value: String,
        ) {
            require(IDENTIFIER_PATTERN.matches(value)) {
                "Invalid JDBC outbox $description '$value': expected [A-Za-z_][A-Za-z0-9_]*"
            }
        }

        private fun requirePrefix(value: String) {
            require(PREFIX_PATTERN.matches(value)) {
                "Invalid JDBC outbox table prefix '$value': expected only letters, digits, or underscores"
            }
        }
    }
}
