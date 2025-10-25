package io.namastack.outbox

import io.namastack.outbox.lock.OutboxLockRepository
import jakarta.persistence.EntityManager
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
     * Creates a TransactionTemplate for programmatic transaction management in outbox operations.
     *
     * This bean is automatically configured using the default PlatformTransactionManager.
     * For multi-persistence unit scenarios, users can provide their own TransactionTemplate bean
     * with a specific transaction manager.
     *
     * @param transactionManager The platform transaction manager
     * @return Configured transaction template
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxTransactionTemplate"])
    @ConditionalOnBean(PlatformTransactionManager::class)
    fun outboxTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    /**
     * Creates a JPA-based outbox lock repository.
     *
     * Uses the outboxTransactionTemplate for transaction management. For multi-persistence unit
     * scenarios, users can provide their own OutboxLockRepository bean with a specific configuration.
     *
     * @param entityManager JPA entity manager
     * @param outboxTransactionTemplate Transaction template for managing transactions
     * @return JPA outbox lock repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxLockRepository(
        entityManager: EntityManager,
        outboxTransactionTemplate: TransactionTemplate,
    ): OutboxLockRepository =
        JpaOutboxLockRepository(
            entityManager,
            outboxTransactionTemplate,
        )

    /**
     * Creates a JPA-based outbox record repository.
     *
     * Uses the outboxTransactionTemplate for transaction management. For multi-persistence unit
     * scenarios, users can provide their own OutboxRecordRepository bean with a specific configuration.
     *
     * @param entityManager JPA entity manager
     * @param outboxTransactionTemplate Transaction template for managing transactions
     * @param clock Clock for time-based operations
     * @return JPA outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordRepository(
        entityManager: EntityManager,
        outboxTransactionTemplate: TransactionTemplate,
        clock: Clock,
    ): OutboxRecordRepository =
        JpaOutboxRecordRepository(
            entityManager,
            outboxTransactionTemplate,
            clock,
        )

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
        settings.schemaLocations = mutableListOf("classpath:schema/outbox_and_outbox_lock.sql")
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }
}
