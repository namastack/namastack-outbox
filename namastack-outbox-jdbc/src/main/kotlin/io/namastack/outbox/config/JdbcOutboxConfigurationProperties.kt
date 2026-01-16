package io.namastack.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the JDBC outbox module.
 *
 * @property tablePrefix Prefix to apply to all outbox table names (e.g., "my_" results in "my_outbox_record").
 *                       Defaults to empty string (no prefix).
 * @property schemaName Database schema name for outbox tables (e.g., "myschema" results in "myschema.outbox_record").
 *                      Defaults to null (uses default schema).
 * @property schemaInitialization Configuration for automatic schema initialization.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "outbox.jdbc")
data class JdbcOutboxConfigurationProperties(
    var tablePrefix: String = "",
    var schemaName: String? = null,
    var schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
    /**
     * Configuration for automatic database schema initialization.
     *
     * Note: Schema initialization cannot be used together with custom table prefixes or schema names.
     * When using custom naming, create tables manually using Flyway, Liquibase, or plain SQL scripts.
     *
     * @property enabled Whether to automatically create outbox tables at startup. Defaults to false.
     */
    data class SchemaInitialization(
        var enabled: Boolean = false,
    )
}
