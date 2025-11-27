package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.instance.OutboxInstanceRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource

@DisplayName("JpaOutboxAutoConfiguration")
class JpaOutboxAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaOutboxAutoConfiguration::class.java))

    @Nested
    @DisplayName("Core Bean Creation")
    inner class CoreBeanCreation {
        @Test
        fun `creates all required outbox beans with complete configuration`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRepository::class.java)
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasBean("outboxTransactionTemplate")
                    assertThat(context).hasBean("outboxEntityManager")
                    assertThat(context).hasBean("outboxPartitionAssignmentRepository")

                    assertThat(context.getBean("outboxRecordRepository"))
                        .isInstanceOf(JpaOutboxRecordRepository::class.java)
                    assertThat(context.getBean("outboxInstanceRepository"))
                        .isInstanceOf(JpaOutboxInstanceRepository::class.java)
                    assertThat(context.getBean("outboxPartitionAssignmentRepository"))
                        .isInstanceOf(JpaOutboxPartitionAssignmentRepository::class.java)
                }
        }

        @Test
        fun `creates outboxTransactionTemplate bean when PlatformTransactionManager available`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxTransactionTemplate")

                    val transactionTemplate =
                        context.getBean(
                            "outboxTransactionTemplate",
                            TransactionTemplate::class.java,
                        )
                    assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `creates outboxEntityManager bean as alias to primary EntityManager`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxEntityManager")

                    val entityManager = context.getBean("outboxEntityManager", EntityManager::class.java)
                    assertThat(entityManager).isNotNull()
                }
        }
    }

    @Nested
    @DisplayName("Clock Configuration")
    inner class ClockConfiguration {
        @Test
        fun `provides default system clock when none configured`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutClock::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    assertThat(clock.zone).isEqualTo(ZoneId.systemDefault())
                }
        }

        @Test
        fun `uses custom clock when provided`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomClock::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    assertThat(clock.zone).isEqualTo(ZoneId.of("Z"))
                }
        }
    }

    @Nested
    @DisplayName("Schema Initialization")
    inner class SchemaInitialization {
        @Test
        fun `creates database initializer when schema initialization enabled`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithRealDataSource::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }

        @Test
        fun `does not create database initializer when explicitly disabled`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }

        @Test
        fun `does not create database initializer by default`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }

        @Test
        fun `throws on unknown database type`() {
            val dataSource = mockk<DataSource>()

            every { dataSource.connection } throws SQLException("unknown connection")

            val jpaOutboxAutoConfiguration = JpaOutboxAutoConfiguration()

            assertThatThrownBy {
                jpaOutboxAutoConfiguration.outboxDataSourceScriptDatabaseInitializer(dataSource)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining("Could not detect database name")
        }
    }

    @Nested
    @DisplayName("Dependency Validation")
    inner class DependencyValidation {
        @Test
        fun `fails when EntityManager is missing`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutEntityManager::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("EntityManager")
                }
        }

        @Test
        fun `fails when PlatformTransactionManager is missing`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutTransactionManager::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("PlatformTransactionManager")
                }
        }

        @Test
        fun `fails when DataSource is missing for schema initialization`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutDataSource::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("DataSource")
                }
        }
    }

    @Nested
    @DisplayName("Bean Customization")
    inner class BeanCustomization {
        @Test
        fun `allows custom outboxTransactionTemplate override`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomTransactionTemplate::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxTransactionTemplate")

                    val transactionTemplate =
                        context.getBean(
                            "outboxTransactionTemplate",
                            TransactionTemplate::class.java,
                        )
                    assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `allows custom repository implementations`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomRepository::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)

                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    assertThat(repository).isNotInstanceOf(JpaOutboxRecordRepository::class.java)
                }
        }

        @Test
        fun `allows custom outboxEntityManager override`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomEntityManager::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxEntityManager")

                    val entityManager = context.getBean("outboxEntityManager", EntityManager::class.java)
                    assertThat(entityManager).isNotNull()
                }
        }
    }

    @Nested
    @DisplayName("Conditional Activation")
    inner class ConditionalActivation {
        @Test
        fun `does not create any outbox beans when EnableOutbox annotation missing`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutEnableOutbox::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                    assertThat(context).doesNotHaveBean(OutboxInstanceRepository::class.java)
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                    // Clock bean might still be created if it's a fallback
                }
        }
    }

    // Test Configuration Classes

    @EnableOutbox
    @Configuration
    private class CompleteConfiguration {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithRealDataSource {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource =
            EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithoutClock {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithCustomClock {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithoutEntityManager {
        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithoutDataSource {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithoutTransactionManager {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithCustomTransactionTemplate {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean("outboxTransactionTemplate")
        fun customOutboxTransactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithCustomEntityManager {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean("outboxEntityManager")
        fun customOutboxEntityManager(): EntityManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithCustomRepository {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithoutEnableOutbox {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }
}
