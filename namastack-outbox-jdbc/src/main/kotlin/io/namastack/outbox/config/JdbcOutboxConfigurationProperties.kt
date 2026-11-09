package io.namastack.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the JDBC outbox module.
 *
 * @property schemaName Database schema name for outbox tables (e.g., "myschema" results in "myschema.outbox_record").
 *                      Defaults to null (uses default schema).
 * @property tablePrefix Prefix to apply to all outbox table names (e.g., "my_" results in "my_outbox_record").
 *                       Defaults to empty string (no prefix).
 * @property tableNames Base table names to use for the outbox tables. Defaults preserve the built-in names.
 * @property schemaInitialization Configuration for automatic schema initialization.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "namastack.outbox.jdbc")
data class JdbcOutboxConfigurationProperties(
    var schemaName: String? = null,
    var tablePrefix: String = "",
    var tableNames: TableNames = TableNames(),
    var schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
    /**
     * Base table names for the outbox tables.
     *
     * These are combined with [tablePrefix] and [schemaName] to produce the fully qualified table names.
     * Use these to satisfy organization-wide naming standards (e.g. all-uppercase Oracle-style identifiers).
     *
     * Note: Custom table names cannot be used together with automatic schema initialization. When using
     * custom naming, create tables manually using Flyway, Liquibase, or plain SQL scripts.
     *
     * @property record Base table name for outbox records. Defaults to "outbox_record".
     * @property instance Base table name for outbox instances. Defaults to "outbox_instance".
     * @property partition Base table name for outbox partition assignments. Defaults to "outbox_partition".
     */
    data class TableNames(
        var record: String = "outbox_record",
        var instance: String = "outbox_instance",
        var partition: String = "outbox_partition",
    )

    /**
     * Configuration for automatic database schema initialization.
     *
     * Note: Schema initialization cannot be used together with custom table prefixes, schema names or
     * custom table names. When using custom naming, create tables manually using Flyway, Liquibase, or
     * plain SQL scripts.
     *
     * @property enabled Whether to automatically create outbox tables at startup. Defaults to true.
     */
    data class SchemaInitialization(
        var enabled: Boolean = true,
    )
}
