package io.namastack.outbox

import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment

/**
 * Hibernate physical naming strategy that applies a prefix and schema to outbox tables.
 *
 * This naming strategy extends the default snake_case strategy and adds optional
 * prefix and schema configuration to the three core outbox tables:
 * outbox_record, outbox_instance, and outbox_partition.
 *
 * Example with prefix "myapp_":
 * - myapp_outbox_record
 * - myapp_outbox_instance
 * - myapp_outbox_partition
 *
 * Example with schema "outbox_schema" and prefix "myapp_":
 * - outbox_schema.myapp_outbox_record
 * - outbox_schema.myapp_outbox_instance
 * - outbox_schema.myapp_outbox_partition
 *
 * Other tables (user tables) are not affected by this strategy.
 *
 * @param prefix The prefix to apply to outbox tables. Empty string by default (no prefix).
 * @param schema The schema to use for outbox tables. Null by default (uses default schema).
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class PrefixedPhysicalNamingStrategy(
    private val prefix: String = "",
    private val schema: String? = null,
) : PhysicalNamingStrategySnakeCaseImpl() {
    /**
     * Set of outbox table names that should be prefixed.
     */
    private val outboxTables = setOf("outbox_record", "outbox_instance", "outbox_partition")

    /**
     * Converts logical table name to physical table name with optional prefix.
     *
     * Applies the configured prefix only to the three core outbox tables.
     * All other tables use the default snake_case naming strategy.
     *
     * @param logicalName The logical table name from JPA entity
     * @param context JDBC environment context
     * @return Physical table name with prefix applied if applicable
     */
    override fun toPhysicalTableName(
        logicalName: Identifier?,
        jdbcEnvironment: JdbcEnvironment?,
    ): Identifier? {
        if (logicalName == null || prefix.isBlank()) {
            return super.toPhysicalTableName(logicalName, jdbcEnvironment)
        }

        val tableName = logicalName.text
        if (tableName !in outboxTables) {
            return super.toPhysicalTableName(logicalName, jdbcEnvironment)
        }

        return Identifier.toIdentifier(prefix + tableName)
    }

    /**
     * Converts logical schema name to physical schema name.
     *
     * If a custom schema is configured, it will be applied to all outbox tables.
     * Otherwise, the default schema resolution is used.
     *
     * Example: With schema "myschema" and prefix "app_", tables become:
     * - myschema.app_outbox_record
     * - myschema.app_outbox_instance
     * - myschema.app_outbox_partition
     *
     * @param logicalName The logical schema name from JPA entity
     * @param jdbcEnvironment JDBC environment context
     * @return Physical schema name if configured, otherwise default schema
     */
    override fun toPhysicalSchemaName(
        logicalName: Identifier?,
        jdbcEnvironment: JdbcEnvironment?,
    ): Identifier? {
        if (schema.isNullOrBlank()) {
            return super.toPhysicalSchemaName(logicalName, jdbcEnvironment)
        }

        return Identifier.toIdentifier(schema)
    }
}
