package io.namastack.outbox

/**
 * Resolves fully qualified table names for the JDBC outbox repositories.
 *
 * The default implementation ([DefaultJdbcTableNameResolver]) applies the configured schema name,
 * table prefix and base table names. Register a custom bean implementing this interface to take full
 * control over table naming (the auto-configuration registers the default behind
 * `@ConditionalOnMissingBean`).
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
interface JdbcTableNameResolver {
    /**
     * Returns the fully qualified table name for outbox records.
     */
    fun getOutboxRecord(): String

    /**
     * Returns the fully qualified table name for outbox instances.
     */
    fun getOutboxInstance(): String

    /**
     * Returns the fully qualified table name for outbox partition assignments.
     */
    fun getOutboxPartitionAssignment(): String
}
