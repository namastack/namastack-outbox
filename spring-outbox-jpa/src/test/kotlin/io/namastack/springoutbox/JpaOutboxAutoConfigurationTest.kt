package io.namastack.springoutbox

import io.mockk.mockk
import io.namastack.springoutbox.lock.OutboxLockRepository
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
import java.time.Clock
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
                    assertThat(context).hasSingleBean(OutboxLockRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    assertThat(context.getBean(OutboxLockRepository::class.java))
                        .isInstanceOf(JpaOutboxLockRepository::class.java)
                    assertThat(context.getBean(OutboxRecordRepository::class.java))
                        .isInstanceOf(JpaOutboxRecordRepository::class.java)
                }
        }

        @Test
        fun `creates JpaOutboxLockRepository with EntityManager dependency`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxLockRepository::class.java)
                    val repository = context.getBean(OutboxLockRepository::class.java)
                    assertThat(repository).isInstanceOf(JpaOutboxLockRepository::class.java)
                }
        }

        @Test
        fun `creates JpaOutboxRecordRepository with EntityManager and Clock dependencies`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    assertThat(repository).isInstanceOf(JpaOutboxRecordRepository::class.java)
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
                    assertThat(context).doesNotHaveBean(OutboxLockRepository::class.java)
                    assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                    assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                }
        }
    }

    @EnableOutbox
    @Configuration
    private class CompleteTestConfig {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigWithoutEnableOutbox {
        @Bean
        fun entityManager(): EntityManager = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)
    }
}
