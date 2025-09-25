package com.beisel.springoutbox

import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import java.time.Clock
import javax.sql.DataSource

@AutoConfiguration
@AutoConfigureBefore(HibernateJpaAutoConfiguration::class)
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class JpaOutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun outboxLockRepository(entityManager: EntityManager): OutboxLockRepository =
        JpaOutboxLockRepository(entityManager)

    @Bean
    @ConditionalOnProperty(name = ["outbox.schema-initialization.enabled"], havingValue = "true")
    fun outboxDataSourceScriptDatabaseInitializer(dataSource: DataSource): DataSourceScriptDatabaseInitializer {
        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf("classpath:schema/outbox_and_outbox_lock.sql")
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }

    @Bean
    fun outboxLockManager(
        lockRepository: OutboxLockRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxLockManager = OutboxLockManager(lockRepository, properties, clock)

    @Bean
    fun outboxRecordRepository(
        entityManager: EntityManager,
        clock: Clock,
    ): OutboxRecordRepository = JpaOutboxRecordRepository(entityManager, clock)

    @Bean
    @ConditionalOnBean(OutboxRecordRepository::class)
    fun outboxScheduler(
        recordRepository: OutboxRecordRepository,
        recordProcessor: OutboxRecordProcessor,
        lockManager: OutboxLockManager,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxScheduler = OutboxScheduler(recordRepository, recordProcessor, lockManager, properties, clock)
}
