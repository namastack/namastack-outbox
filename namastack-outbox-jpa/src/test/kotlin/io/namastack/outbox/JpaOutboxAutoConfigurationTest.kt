package io.namastack.outbox

import io.mockk.mockk
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId

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
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasSingleBean(Clock::class.java)
                    Assertions.assertThat(context).hasBean("outboxEntityManager")
                    Assertions.assertThat(context).hasBean("outboxTransactionTemplate")
                    Assertions.assertThat(context).hasSingleBean(OutboxRecordEntityMapper::class.java)
                    Assertions.assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    Assertions.assertThat(context).hasSingleBean(OutboxInstanceRepository::class.java)
                    Assertions
                        .assertThat(context)
                        .hasSingleBean(JpaOutboxPartitionAssignmentRepository::class.java)
                }
        }

        @Test
        fun `creates outboxTransactionTemplate bean when PlatformTransactionManager available`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasBean("outboxTransactionTemplate")

                    val transactionTemplate =
                        context.getBean(
                            "outboxTransactionTemplate",
                            TransactionTemplate::class.java,
                        )
                    Assertions.assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `creates outboxEntityManager bean as alias to primary EntityManager`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasBean("outboxEntityManager")

                    val entityManager = context.getBean("outboxEntityManager", EntityManager::class.java)
                    Assertions.assertThat(entityManager).isNotNull()
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
                    Assertions.assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    Assertions.assertThat(clock.zone).isEqualTo(ZoneId.systemDefault())
                }
        }

        @Test
        fun `uses custom clock when provided`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomClock::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    Assertions.assertThat(clock.zone).isEqualTo(ZoneId.of("Z"))
                }
        }
    }

    @Nested
    @DisplayName("Dependency Validation")
    inner class DependencyValidation {
        @Test
        fun `fails when EntityManager is missing`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutEntityManagerFactory::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasFailed()
                    Assertions
                        .assertThat(context.getStartupFailure())
                        .hasMessageContaining("EntityManager")
                }
        }

        @Test
        fun `fails when PlatformTransactionManager is missing`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithoutTransactionManager::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasFailed()
                    Assertions
                        .assertThat(context.getStartupFailure())
                        .hasMessageContaining("PlatformTransactionManager")
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
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasBean("outboxTransactionTemplate")

                    val transactionTemplate =
                        context.getBean(
                            "outboxTransactionTemplate",
                            TransactionTemplate::class.java,
                        )
                    Assertions.assertThat(transactionTemplate).isNotNull()
                }
        }

        @Test
        fun `allows custom repository implementations`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomRepository::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)

                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    Assertions.assertThat(repository).isNotInstanceOf(JpaOutboxRecordRepository::class.java)
                }
        }

        @Test
        fun `allows custom outboxEntityManager override`() {
            contextRunner
                .withUserConfiguration(ConfigurationWithCustomEntityManager::class.java)
                .run { context ->
                    Assertions.assertThat(context).hasNotFailed()
                    Assertions.assertThat(context).hasBean("outboxEntityManager")

                    val entityManager = context.getBean("outboxEntityManager", EntityManager::class.java)
                    Assertions.assertThat(entityManager).isNotNull()
                }
        }
    }

    @Nested
    @DisplayName("Conditional Activation")
    inner class ConditionalActivation {
        @Test
        fun `does not create any outbox beans when outbox disabled`() {
            contextRunner
                .withUserConfiguration(CompleteConfiguration::class.java)
                .withPropertyValues("outbox.enabled=false")
                .run { context ->
                    Assertions.assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                    Assertions.assertThat(context).doesNotHaveBean(OutboxInstanceRepository::class.java)
                    Assertions.assertThat(context).doesNotHaveBean(PartitionAssignmentRepository::class.java)
                }
        }
    }

    // Test Configuration Classes

    @Configuration
    private class CompleteConfiguration {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithoutClock {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithCustomClock {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @Configuration
    private class ConfigurationWithoutEntityManagerFactory {
        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithoutTransactionManager {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @Configuration
    private class ConfigurationWithCustomTransactionTemplate {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean("outboxTransactionTemplate")
        fun customOutboxTransactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithCustomEntityManager {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean("outboxEntityManager")
        fun customOutboxEntityManager(): EntityManager = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithCustomRepository {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)
    }
}
