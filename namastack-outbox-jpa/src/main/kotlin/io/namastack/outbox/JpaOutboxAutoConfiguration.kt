package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DatabaseMetaData
import java.time.Clock
import javax.sql.DataSource

/**
 * Auto-configuration class for JPA-based Outbox functionality.
 *
 * This configuration provides JPA implementations for outbox repositories
 * and handles database schema initialization when enabled.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(HibernateJpaAutoConfiguration::class)
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
class JpaOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a named EntityManager for outbox operations.
     *
     * This bean uses the primary EntityManager by default, but can be overridden
     * to use a specific EntityManager for multi-database scenarios.
     *
     * @param entityManager Primary JPA entity manager
     * @return Named entity manager for outbox operations
     */
    @Bean("outboxEntityManager")
    @ConditionalOnMissingBean(name = ["outboxEntityManager"])
    fun outboxEntityManager(entityManager: EntityManager): EntityManager = entityManager

    /**
     * Creates a transaction template for outbox operations.
     *
     * @param transactionManager Platform transaction manager
     * @return Transaction template for outbox operations
     */
    @Bean("outboxTransactionTemplate")
    @ConditionalOnMissingBean(name = ["outboxTransactionTemplate"])
    fun outboxTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    /**
     * Creates a JPA-based outbox record repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @param clock Clock for time-based operations
     * @return JPA outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordRepository(
        @Qualifier("outboxEntityManager") entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
        clock: Clock,
    ): OutboxRecordRepository = JpaOutboxRecordRepository(entityManager, transactionTemplate, clock)

    /**
     * Creates a JPA-based outbox instance repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @return JPA outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceRepository(
        @Qualifier("outboxEntityManager") entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): OutboxInstanceRepository = JpaOutboxInstanceRepository(entityManager, transactionTemplate)

    /**
     * Creates a JPA-based outbox partition repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @param clock Clock for time-based operations
     * @return JPA outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxPartitionAssignmentRepository(
        @Qualifier("outboxEntityManager") entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): PartitionAssignmentRepository = JpaOutboxPartitionAssignmentRepository(entityManager, transactionTemplate)

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
    fun outboxDataSourceScriptDatabaseInitializer(dataSource: DataSource): DataSourceScriptDatabaseInitializer {
        val databaseName = detectDatabaseName(dataSource)
        val databaseType = DatabaseType.from(databaseName)

        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf(databaseType.schemaLocation)
        settings.mode = DatabaseInitializationMode.ALWAYS

        if (databaseType == DatabaseType.Oracle) {
            settings.isContinueOnError = true
        }

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }

    private fun detectDatabaseName(dataSource: DataSource): String =
        try {
            val metadata = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
            JdbcUtils.commonDatabaseName(metadata)
        } catch (e: Exception) {
            throw RuntimeException("Could not detect database name", e)
        }
}
