package io.namastack.outbox.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.support.JdbcUtils
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/**
 * Auto-configuration for JDBC outbox database schema initialization.
 *
 * This configuration handles database schema creation when enabled.
 * Requires a DataSource to be present.
 *
 * @author Roland Beisel, Khalid Alharisi
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(DataSource::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class JdbcOutboxSchemaAutoConfiguration {
    /**
     * Creates a database initializer for outbox schema when schema initialization is enabled.
     *
     * This bean is created by default unless the property 'outbox.jdbc.schema-initialization.enabled' is set to false.
     *
     * @param dataSource The data source to initialize
     * @return Database initializer for outbox schema
     */
    @Bean
    @ConditionalOnProperty(
        name = ["namastack.outbox.jdbc.schema-initialization.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    internal fun outboxDataSourceScriptDatabaseInitializer(
        dataSource: DataSource,
        properties: JdbcOutboxConfigurationProperties,
    ): DataSourceScriptDatabaseInitializer {
        validateNoCustomTableNaming(properties)

        val databaseName = detectDatabaseName(dataSource)
        val databaseType = JdbcDatabaseType.from(databaseName)

        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf(databaseType.schemaLocation)
        settings.separator = databaseType.statementSeparator
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }

    private fun validateNoCustomTableNaming(properties: JdbcOutboxConfigurationProperties) {
        val hasTablePrefix = properties.tablePrefix.isNotEmpty()
        val hasSchemaName = !properties.schemaName.isNullOrEmpty()

        if (hasTablePrefix || hasSchemaName) {
            throw IllegalStateException(
                "Cannot use automatic schema initialization " +
                    "(namastack.outbox.jdbc.schema-initialization.enabled=true) together with custom table prefix or " +
                    "schema name. Either disable schema initialization and create tables manually with your desired " +
                    "naming, or remove the table-prefix and schema-name configuration.",
            )
        }
    }

    private fun detectDatabaseName(dataSource: DataSource): String =
        try {
            val metadata = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
            JdbcUtils.commonDatabaseName(metadata) ?: throw RuntimeException("Could not detect database name")
        } catch (e: Exception) {
            throw RuntimeException("Could not detect database name", e)
        }
}
