package io.namastack.outbox.config

import io.namastack.outbox.annotation.EnableOutbox
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.support.JdbcUtils
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/**
 * Auto-configuration for JPA outbox database schema initialization.
 *
 * This configuration runs before Hibernate JPA initialization to ensure
 * outbox tables are created before entity scanning occurs.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(HibernateJpaAutoConfiguration::class)
@ConditionalOnBean(annotation = [EnableOutbox::class])
class JpaOutboxSchemaAutoConfiguration {
    /**
     * Creates a database initializer for outbox schema when schema initialization is enabled.
     *
     * This bean runs before Hibernate JPA auto-configuration to ensure tables exist
     * before entity scanning occurs.
     *
     * @param dataSource The data source to initialize
     * @return Database initializer for outbox schema
     */
    @Bean
    @ConditionalOnProperty(name = ["outbox.schema-initialization.enabled"], havingValue = "true")
    internal fun outboxDataSourceScriptDatabaseInitializer(
        dataSource: DataSource,
    ): DataSourceScriptDatabaseInitializer {
        val databaseName = detectDatabaseName(dataSource)
        val databaseType = DatabaseType.from(databaseName)

        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf(databaseType.schemaLocation)
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }

    private fun detectDatabaseName(dataSource: DataSource): String =
        try {
            val metadata = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
            JdbcUtils.commonDatabaseName(metadata) ?: throw RuntimeException("Could not detect database name")
        } catch (e: Exception) {
            throw RuntimeException("Could not detect database name", e)
        }
}
