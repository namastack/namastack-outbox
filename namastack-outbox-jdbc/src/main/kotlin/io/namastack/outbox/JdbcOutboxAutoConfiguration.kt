package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DatabaseMetaData
import java.time.Clock
import javax.sql.DataSource

/**
 * Auto-configuration class for JDBC-based Outbox functionality.
 *
 * This configuration provides JDBC implementations for outbox repositories
 * and handles database schema initialization when enabled.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
@ConditionalOnBean(annotation = [EnableOutbox::class])
class JdbcOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a named JdbcTemplate for outbox operations.
     *
     * Users can override this by providing their own "outboxJdbcTemplate" bean.
     *
     * @param dataSource DataSource to use
     * @return JdbcTemplate for outbox operations
     */
    @Bean("outboxJdbcTemplate")
    @ConditionalOnMissingBean(name = ["outboxJdbcTemplate"])
    internal fun outboxJdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

    /**
     * Creates a transaction template for outbox operations.
     *
     * @param transactionManager Platform transaction manager
     * @return Transaction template for outbox operations
     */
    @Bean("outboxTransactionTemplate")
    @ConditionalOnMissingBean(name = ["outboxTransactionTemplate"])
    internal fun outboxTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    /**
     * Creates a JDBC-based outbox record repository.
     *
     * @param jdbcTemplate JDBC template
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @param recordSerializer Serializer for payload and context
     * @param clock Clock for time-based operations
     * @return JDBC outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordRepository(
        @Qualifier("outboxJdbcTemplate") jdbcTemplate: JdbcTemplate,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
        recordSerializer: OutboxPayloadSerializer,
        clock: Clock,
    ): OutboxRecordRepository = JdbcOutboxRecordRepository(jdbcTemplate, transactionTemplate, recordSerializer, clock)

    /**
     * Creates a JDBC-based outbox instance repository.
     *
     * @param jdbcTemplate JDBC template
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @return JDBC outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(
        @Qualifier("outboxJdbcTemplate") jdbcTemplate: JdbcTemplate,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): OutboxInstanceRepository = JdbcOutboxInstanceRepository(jdbcTemplate, transactionTemplate)

    /**
     * Creates a JDBC-based outbox partition repository.
     *
     * @param jdbcTemplate JDBC template
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @return JDBC outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxPartitionAssignmentRepository(
        @Qualifier("outboxJdbcTemplate") jdbcTemplate: JdbcTemplate,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): PartitionAssignmentRepository = JdbcOutboxPartitionAssignmentRepository(jdbcTemplate, transactionTemplate)

    /**
     * Creates a database initializer for outbox schema when schema initialization is enabled.
     *
     * This bean is only created when the property 'outbox.schema-initialization.enabled' is set to true.
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
        val databaseType = JdbcDatabaseType.from(databaseName)

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
