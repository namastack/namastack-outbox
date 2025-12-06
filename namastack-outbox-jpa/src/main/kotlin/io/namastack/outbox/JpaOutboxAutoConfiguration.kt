package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.orm.jpa.SharedEntityManagerCreator
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
     * This creates a shared, transaction-aware EntityManager proxy that automatically
     * participates in Spring-managed transactions. Users can override this by providing
     * their own "outboxEntityManager" bean.
     *
     * @param entityManagerFactory Primary JPA entity manager factory
     * @return Shared, transaction-aware entity manager for outbox operations
     */
    @Bean("outboxEntityManager")
    @ConditionalOnMissingBean(name = ["outboxEntityManager"])
    internal fun outboxEntityManager(entityManagerFactory: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)

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
     * Creates a JPA-based outbox record repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @param clock Clock for time-based operations
     * @return JPA outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordRepository(
        @Qualifier("outboxEntityManager") entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
        outboxRecordEntityMapper: OutboxRecordEntityMapper,
        clock: Clock,
    ): OutboxRecordRepository =
        JpaOutboxRecordRepository(entityManager, transactionTemplate, outboxRecordEntityMapper, clock)

    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordEntityMapper(recordSerializer: OutboxPayloadSerializer): OutboxRecordEntityMapper =
        OutboxRecordEntityMapper(recordSerializer)

    /**
     * Creates a JPA-based outbox instance repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @return JPA outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(
        @Qualifier("outboxEntityManager") entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): OutboxInstanceRepository = JpaOutboxInstanceRepository(entityManager, transactionTemplate)

    /**
     * Creates a JPA-based outbox partition repository.
     *
     * @param entityManager JPA entity manager
     * @param transactionTemplate Transaction template for programmatic transaction management
     * @return JPA outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxPartitionAssignmentRepository(
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
