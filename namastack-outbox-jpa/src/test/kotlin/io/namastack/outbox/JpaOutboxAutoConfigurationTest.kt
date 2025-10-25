package io.namastack.outbox

import io.mockk.mockk
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource

@DisplayName("JpaOutboxAutoConfiguration")
class JpaOutboxAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaOutboxAutoConfiguration::class.java))

    @Nested
    @DisplayName("When @EnableOutbox annotation is present")
    inner class WithEnableOutboxAnnotation {
        @Test
        fun `creates all required JPA beans`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRepository::class.java)
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasBean("outboxTransactionTemplate")

                    assertThat(context.getBean(OutboxRecordRepository::class.java))
                        .isInstanceOf(JpaOutboxRecordRepository::class.java)
                    assertThat(context.getBean(OutboxInstanceRepository::class.java))
                        .isInstanceOf(JpaOutboxInstanceRepository::class.java)
                }
        }

        @Test
        fun `creates outboxTransactionTemplate bean when PlatformTransactionManager is available`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxTransactionTemplate")

                    val transactionTemplate =
                        context.getBean("outboxTransactionTemplate", TransactionTemplate::class.java)
                    assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `creates JpaOutboxRecordRepository with correct dependencies`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    assertThat(repository).isInstanceOf(JpaOutboxRecordRepository::class.java)
                }
        }

        @Test
        fun `creates JpaOutboxInstanceRepository with EntityManager dependency`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    val repository = context.getBean(OutboxInstanceRepository::class.java)
                    assertThat(repository).isInstanceOf(JpaOutboxInstanceRepository::class.java)
                }
        }

        @Test
        fun `provides default Clock when none configured`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutClock::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    assertThat(clock.zone).isEqualTo(ZoneId.systemDefault())
                }
        }

        @Test
        fun `uses custom Clock when provided`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomClock::class.java)
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
            contextRunner()
                .withUserConfiguration(ConfigWithRealDataSource::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }

        @Test
        fun `does not create database initializer when schema initialization disabled`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }

        @Test
        fun `does not create database initializer by default`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Conditional Bean Creation")
    inner class ConditionalBeanCreation {
        @Test
        fun `fails when EntityManager is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutEntityManager::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("EntityManager")
                }
        }

        @Test
        fun `fails when DataSource is missing for schema initialization`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutDataSource::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("DataSource")
                }
        }

        @Test
        fun `fails when PlatformTransactionManager is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutTransactionManager::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("PlatformTransactionManager")
                }
        }

        @Test
        fun `creates beans when all dependencies are available`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRepository::class.java)
                    assertThat(context).hasSingleBean(Clock::class.java)
                    // Schema initializer should not be created by default
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }
    }

    @Nested
    @DisplayName("When @EnableOutbox annotation is missing")
    inner class WithoutEnableOutboxAnnotation {
        @Test
        fun `does not create any JPA outbox beans`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutEnableOutbox::class.java)
                .withPropertyValues("outbox.schema-initialization.enabled=true")
                .run { context ->
                    assertThat(context).doesNotHaveBean(Clock::class.java)
                    assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                    assertThat(context).doesNotHaveBean(OutboxInstanceRepository::class.java)
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Bean Override Scenarios")
    inner class BeanOverrideScenarios {
        @Test
        fun `allows custom outboxTransactionTemplate override`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomTransactionTemplate::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasBean("outboxTransactionTemplate")

                    // Should use the custom transaction template from test config
                    val transactionTemplate =
                        context.getBean("outboxTransactionTemplate", TransactionTemplate::class)
                    assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `allows custom repository implementations`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomRepository::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)

                    // Should use the custom implementation from test config
                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    assertThat(repository).isNotInstanceOf(JpaOutboxRecordRepository::class.java)
                }

            contextRunner()
                .withUserConfiguration(ConfigWithCustomRepository::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    // Should use the custom implementation from test config
                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    assertThat(repository).isNotInstanceOf(JpaOutboxRecordRepository::class.java)
                }
        }
    }

    // Test Configurations

    @EnableOutbox
    @Configuration
    private class CompleteTestConfig {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithRealDataSource {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource =
            EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutClock {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomClock {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutEntityManager {
        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutDataSource {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutTransactionManager {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        // No PlatformTransactionManager bean
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomTransactionTemplate {
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
    private class ConfigWithCustomRepository {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigWithoutEnableOutbox {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }
}
