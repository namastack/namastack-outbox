package io.namastack.outbox

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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
internal class JpaOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

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
        entityManager: EntityManager,
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
        entityManager: EntityManager,
        @Qualifier("outboxTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): OutboxInstanceRepository = JpaOutboxInstanceRepository(entityManager, transactionTemplate)

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
        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf("classpath:schema/outbox-tables.sql")
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }
}
